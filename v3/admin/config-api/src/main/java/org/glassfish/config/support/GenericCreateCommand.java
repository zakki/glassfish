/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2009-2010 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.config.support;

import com.sun.hk2.component.ExistingSingletonInhabitant;
import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.*;
import org.glassfish.api.admin.config.Named;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.component.*;
import org.jvnet.hk2.config.*;
import com.sun.logging.LogDomains;

import java.util.logging.Logger;
import java.util.logging.Level;
import java.beans.PropertyVetoException;

/**
 * Generic create command implementation.
 *
 * This command can create POJO configuration objects from an asadmin command
 * invocation parameters.
 *
 * So far, such POJO must be ConfigBeanProxy subclasses and be annotated with the
 * {@see Param} annotation to property function. 
 *
 * @author Jerome Dochez
 */
@Scoped(PerLookup.class)
public class GenericCreateCommand extends GenericCrudCommand implements AdminCommand, PostConstruct, CommandModelProvider {

    @Inject
    Habitat habitat;
    
    Class<? extends CrudResolver> resolverType;
    CommandModel model;
    String elementName;
    Create create;


    final static Logger logger = LogDomains.getLogger(GenericCreateCommand.class, LogDomains.ADMIN_LOGGER);

    public void postConstruct() {

        super.postConstruct();

        create = targetType.getAnnotation(Create.class);
        resolverType = create.resolver();
        try {
            elementName = elementName(document, create.parentType(), targetType);
        } catch (ClassNotFoundException e) {
            logger.log(Level.SEVERE, "Cannot load child type", e);
            String msg = localStrings.getLocalString(GenericCrudCommand.class,
                    "GenericCrudCommand.configbean_not_found",
                    "The Config Bean {0} cannot be loaded by the generic command implementation : {1}",
                    create.parentType(), e.getMessage());
            logger.severe(msg);
            throw new ComponentException(msg, e);            
        }

        if (logger.isLoggable(Level.FINE)) {
            logger.fine("Generic Command configured for creating " + targetType.getName() + " instances which gets added to " +
                create.parentType().getName() + " under " + elementName);
        }

        try {
            model = new GenericCommandModel(targetType, habitat.getComponent(DomDocument.class), commandName, create.resolver(), create.decorator());
            if (logger.isLoggable(Level.FINE)) {
                for (String paramName : model.getParametersNames()) {
                    CommandModel.ParamModel param = model.getModelFor(paramName);
                    logger.fine("I take " + param.getName() + " parameters");
                }
            }
        } catch(Exception e) {
            String msg = localStrings.getLocalString(GenericCrudCommand.class,
                    "GenericCreateCommand.command_model_exception",
                    "Exception while creating the command model for the generic command {0} : {1}",
                    commandName, e.getMessage());
            logger.severe(msg);
            throw new ComponentException(msg, e);

        }        

    }




    public void execute(final AdminCommandContext context) {

        final ActionReport result = context.getActionReport();
        
        // inject resolver with command parameters...
        final InjectionManager manager = new InjectionManager();

        CrudResolver resolver = habitat.getComponent(resolverType);

        manager.inject(resolver, getInjectionResolver());

        final ConfigBeanProxy parentBean = resolver.resolve(context, create.parentType());
        if (parentBean==null) {
            String msg = localStrings.getLocalString(GenericCrudCommand.class,
                    "GenericCreateCommand.target_object_not_found",
                    "The CrudResolver {0} could not find the configuration object of type {1} where instances of {2} should be added",
                    resolver.getClass().toString(), create.parentType(), targetType);
            result.failure(logger, msg);
            return;
        }
        
        try {
            ConfigSupport.apply(new SingleConfigCode<ConfigBeanProxy> () {
                public Object run(ConfigBeanProxy writeableParentBean) throws PropertyVetoException, TransactionFailure {


                    ConfigBeanProxy childBean = writeableParentBean.createChild(targetType);
                    manager.inject(childBean, targetType, getInjectionResolver());

                    String name = null;
                    if (Named.class.isAssignableFrom(targetType)) {
                        name = ((Named) childBean).getName();

                    }

                    // check that such instance does not exist yet...
                    if (name!=null) {
                        Object cbp = habitat.getComponent(targetType.getName(), name);
                        if (cbp!=null) {
                            String msg = localStrings.getLocalString(GenericCrudCommand.class,
                                    "GenericCreateCommand.already_existing_instance",
                                    "A {0} instance with a \"{1}\" name already exist in the configuration",
                                    targetType.getSimpleName(), name);
                            result.failure(logger, msg);
                            throw new TransactionFailure(msg);
                        }
                    }

                    Dom parentDom = Dom.unwrap(writeableParentBean);
                    parentDom.insertAfter(null, elementName, Dom.unwrap(childBean));
                    CreationDecorator<ConfigBeanProxy> decorator = habitat.getComponent(create.decorator());
                    if (decorator==null) {
                        String msg = localStrings.getLocalString(GenericCrudCommand.class,
                                "GenericCreateCommand.decorator_not_found",
                                "The CreationDecorator {0} could not be found in the habitat, is it annotated with @Service ?",
                                create.decorator().toString());
                        result.failure(logger, msg);
                        throw new TransactionFailure(msg);
                    } else {
                        decorator.decorate(context, childBean);
                    }

                    // Now we need to add our new configuration object to the habitat so it can looked up
                    // just like if it had been read from the domain.xml
                    Dom childDom = Dom.unwrap(childBean);
                    habitat.addIndex(new ExistingSingletonInhabitant<ConfigBeanProxy>(childDom.<ConfigBeanProxy>createProxy()), targetType.getName(), name);
                    return childBean;
                }
            }, parentBean);
        } catch(TransactionFailure e) {
            String msg = localStrings.getLocalString(GenericCrudCommand.class,
                    "GenericCreateCommand.transaction_exception",
                    "Exception while adding the new configuration {0}",
                    e.toString());
            logger.log(Level.SEVERE, msg, e);
            result.failure(logger, msg);
        }
    }

    public CommandModel getModel() {
        return model;
    }
}
