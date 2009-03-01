/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
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
package com.sun.enterprise.deployment;

import com.sun.enterprise.util.LocalStringManagerImpl;
import com.sun.enterprise.deployment.util.EjbVisitor;
import org.glassfish.internal.api.Globals;

import java.util.*;
import java.lang.reflect.Method;

import com.sun.enterprise.deployment.util.TypeUtil;

import java.util.concurrent.TimeUnit;

/**
    * Objects of this kind represent the deployment information describing a single 
    * Session Ejb : { stateful , stateless, singleton }
    *@author Danny Coward
    */

public class EjbSessionDescriptor extends EjbDescriptor {


    private Set<LifecycleCallbackDescriptor> postActivateDescs =
        new HashSet<LifecycleCallbackDescriptor>();
    private Set<LifecycleCallbackDescriptor> prePassivateDescs =
        new HashSet<LifecycleCallbackDescriptor>();

    // For EJB 3.0 stateful session beans, information about the assocation
    // between a business method and bean removal.
    private Map<MethodDescriptor, EjbRemovalInfo> removeMethods
        = new HashMap<MethodDescriptor, EjbRemovalInfo>();

    // For EJB 3.0 stateful session beans with adapted homes, list of
    // business methods corresponding to Home/LocalHome create methods.
    private Set<EjbInitInfo> initMethods=new HashSet<EjbInitInfo>();

    private MethodDescriptor afterBeginMethod = null;
    private MethodDescriptor beforeCompletionMethod = null;
    private MethodDescriptor afterCompletionMethod = null;

    // Holds @StatefulTimeout or stateful-timeout from
    // ejb-jar.xml.  Only applies to stateful session beans.
    // Initialize to "not set"(null) state so annotation processing
    // can apply the correct overriding behavior.
    private Long statefulTimeoutValue = null;
    private TimeUnit statefulTimeoutUnit;

    /** The Session type String.*/
    public final static String TYPE = "Session";
    /** The String to indicate stalessness. */
    public final static String STATELESS = "Stateless";
    /** Idicates statefullness of a session ejb.*/
    public final static String STATEFUL = "Stateful";
    
    public final static String SINGLETON = "Singleton";

    private boolean isStateless = false;
    private boolean isStateful  = false;
    private boolean isSingleton = false;

    private List<MethodDescriptor> readLockMethods = new ArrayList<MethodDescriptor>();
    private List<MethodDescriptor> writeLockMethods = new ArrayList<MethodDescriptor>();
    private List<AccessTimeoutHolder> accessTimeoutMethods =
            new ArrayList<AccessTimeoutHolder>();

    private List<MethodDescriptor> tempAsyncMethodsFromXml =
        new ArrayList<MethodDescriptor>();

    // Controls eager vs. lazy Singleton initialization
    private Boolean initOnStartup = null;

    private static final String[] _emptyDepends = new String[] {};

    private String[] dependsOn = _emptyDepends;


    private ConcurrencyManagementType concurrencyManagementType;
    
    private static LocalStringManagerImpl localStrings =
	    new LocalStringManagerImpl(EjbSessionDescriptor.class); 

    /**
	*  Default constructor.
	*/
    public EjbSessionDescriptor() {
    }

    
	/**
	* Returns the type of this bean - always "Session".
	*/
    public String getType() {
	    return TYPE;
    }
    
    /**
    * Returns the string STATELESS or STATEFUL according as to whether
    * the bean is stateless or stateful.
    **/
    
    public String getSessionType() {
	    if (this.isStateless()) {
	        return STATELESS;
	    } else if( isStateful() ){
	        return STATEFUL;
	    } else {
            return SINGLETON;
        }
    }
    
	/** 
	* Accepts the Strings STATELESS / STATEFUL / SINGLETON
	*/
    public void setSessionType(String sessionType) {
	    if (STATELESS.equals(sessionType)) {
	       isStateless = true;
	    } else if(STATEFUL.equals(sessionType)) {
	       isStateful = true;
        } else if(SINGLETON.equals(sessionType)){
            isSingleton = true;
        } else {
            if (this.isBoundsChecking()) {
	        throw new IllegalArgumentException(localStrings.getLocalString(
		        "enterprise.deployment.exceptionsessiontypenotlegaltype",
		        "{0} is not a legal session type for session ejbs. The type must be {1} or {2}",
                new Object[] {sessionType, STATEFUL, STATELESS}));
	        }

	    }
        return;
    }
    
	/**
	* Sets my type
	*/
    public void setType(String type) {
	    throw new IllegalArgumentException(localStrings.getLocalString(
								   "enterprise.deployment.exceptioncannotsettypeofsessionbean",
								   "Cannot set the type of a session bean"));
    }
    

    
	/**
	*  Sets the transaction type for this bean. Must be either BEAN_TRANSACTION_TYPE or CONTAINER_TRANSACTION_TYPE.
	*/
    public void setTransactionType(String transactionType) {
	    boolean isValidType = (BEAN_TRANSACTION_TYPE.equals(transactionType) ||
				CONTAINER_TRANSACTION_TYPE.equals(transactionType));
				
	    if (!isValidType && this.isBoundsChecking()) {
	        throw new IllegalArgumentException(localStrings.getLocalString(
									   "enterprise.deployment..exceptointxtypenotlegaltype",
									   "{0} is not a legal transaction type for session beans", new Object[] {transactionType}));
	    } else {
	        super.transactionType = transactionType;
	        super.setMethodContainerTransactions(new Hashtable());

	    }
    }
    
	/**
	* Returns true if I am describing a stateless session bean.
	*/
    public boolean isStateless() {
	    return isStateless;
    }
    
    public boolean isStateful() {
        return isStateful;
    }

    public boolean isSingleton() {
        return isSingleton;
    }

    public void addAsynchronousMethodFromXml(MethodDescriptor m) {

        // Since asynchronous is represented as a flag on the actual
        // client method descriptors, we need to delay the processing because
        // those lists of client descriptors won't necessarily be available
        // at the time this is called from the .xml.

        // Keep in a temporary list for later processing
        tempAsyncMethodsFromXml.add(m);
    }

    public void addStatefulTimeoutDescriptor(TimeoutValueDescriptor timeout) {
        statefulTimeoutValue = timeout.getValue();
        statefulTimeoutUnit  = timeout.getUnit();
    }

    public void setStatefulTimeout(Long value, TimeUnit unit) {
        statefulTimeoutValue = value;
        statefulTimeoutUnit = unit;
    }

    public boolean hasStatefulTimeout() {
        return (statefulTimeoutValue != null);
    }

    public Long getStatefulTimeoutValue() {
        return statefulTimeoutValue;
    }

    public TimeUnit getStatefulTimeoutUnit() {
        return statefulTimeoutUnit;
    }

    public boolean hasRemoveMethods() {
        return (!removeMethods.isEmpty());
    }

    /**
     * @return remove method info for the given method or null if the
     * given method is not a remove method for this stateful session bean.
     */
    public EjbRemovalInfo getRemovalInfo(MethodDescriptor method) {
        // first try to find the exact match
        for (MethodDescriptor methodDesc : removeMethods.keySet()) {
            if (methodDesc.equals(method)) {
                return removeMethods.get(methodDesc);
            }
        }

        // if nothing is found, try to find the loose match
        for (MethodDescriptor methodDesc : removeMethods.keySet()) {
            if (methodDesc.implies(method)) {
                return removeMethods.get(methodDesc);
            }
        }

        return null;
    }

    public Set<EjbRemovalInfo> getAllRemovalInfo() {
        return new HashSet<EjbRemovalInfo>(removeMethods.values());
    }

    public void addRemoveMethod(EjbRemovalInfo removalInfo) {
        removeMethods.put(removalInfo.getRemoveMethod(), removalInfo);
    }

    public boolean hasInitMethods() {
        return (!initMethods.isEmpty());
    }

    public Set<EjbInitInfo> getInitMethods() {
        return new HashSet<EjbInitInfo>(initMethods);
    }

    public void addInitMethod(EjbInitInfo initInfo) {
        initMethods.add(initInfo);
    }
    
    public Set<LifecycleCallbackDescriptor> getPostActivateDescriptors() {
        if (postActivateDescs == null) {
            postActivateDescs = 
                new HashSet<LifecycleCallbackDescriptor>(); 
        }
        return postActivateDescs;
    }   
            
    public void addPostActivateDescriptor(LifecycleCallbackDescriptor
        postActivateDesc) {
        String className = postActivateDesc.getLifecycleCallbackClass();
        boolean found = false;
        for (LifecycleCallbackDescriptor next :
             getPostActivateDescriptors()) {
            if (next.getLifecycleCallbackClass().equals(className)) {
                found = true;
                break;
            }
        }
        if (!found) {
            getPostActivateDescriptors().add(postActivateDesc);
        }
    }

    public LifecycleCallbackDescriptor 
        getPostActivateDescriptorByClass(String className) {

        for (LifecycleCallbackDescriptor next :
                 getPostActivateDescriptors()) {
            if (next.getLifecycleCallbackClass().equals(className)) {
                return next;
            }
        }
        return null;
    }

    public boolean hasPostActivateMethod() {
        return (getPostActivateDescriptors().size() > 0);
    }

    public Set<LifecycleCallbackDescriptor> getPrePassivateDescriptors() {
        if (prePassivateDescs == null) {
            prePassivateDescs = 
                new HashSet<LifecycleCallbackDescriptor>(); 
        }
        return prePassivateDescs;
    }   
            
    public void addPrePassivateDescriptor(LifecycleCallbackDescriptor
        prePassivateDesc) {
        String className = prePassivateDesc.getLifecycleCallbackClass();
        boolean found = false;
        for (LifecycleCallbackDescriptor next :
             getPrePassivateDescriptors()) {
            if (next.getLifecycleCallbackClass().equals(className)) {
                found = true;
                break;
            }
        }
        if (!found) {
            getPrePassivateDescriptors().add(prePassivateDesc);
        }
    }

    public LifecycleCallbackDescriptor 
        getPrePassivateDescriptorByClass(String className) {

        for (LifecycleCallbackDescriptor next :
                 getPrePassivateDescriptors()) {
            if (next.getLifecycleCallbackClass().equals(className)) {
                return next;
            }
        }
        return null;
    }

    public boolean hasPrePassivateMethod() {
        return (getPrePassivateDescriptors().size() > 0);
    }

    public Vector getPossibleTransactionAttributes() {
        Vector txAttributes = super.getPossibleTransactionAttributes();

        // Session beans that implement SessionSynchronization interface
        // have a limited set of possible transaction attributes.
        if( isStateful() ) {
            try {
                EjbBundleDescriptor ejbBundle = getEjbBundleDescriptor();

                ClassLoader classLoader = ejbBundle.getClassLoader();
                Class ejbClass = classLoader.loadClass(getEjbClassName());

                AnnotationTypesProvider provider = Globals.getDefaultHabitat().getComponent(AnnotationTypesProvider.class, "EJB");
                if (provider!=null) {
                    Class sessionSynchClass = provider.getType("javax.ejb.SessionSynchronization");
                    if( sessionSynchClass.isAssignableFrom(ejbClass) ) {
                        txAttributes = new Vector();
                        txAttributes.add(new ContainerTransaction
                            (ContainerTransaction.REQUIRED, ""));
                        txAttributes.add(new ContainerTransaction
                            (ContainerTransaction.REQUIRES_NEW, ""));
                        txAttributes.add(new ContainerTransaction
                            (ContainerTransaction.MANDATORY, ""));
                    }
                }
            } catch(Exception e) {
                // Don't treat this as a fatal error.  Just return full
                // set of possible transaction attributes.
            }
        }
        return txAttributes;
    }

    public void addAfterBeginDescriptor(MethodDescriptor m) {
        afterBeginMethod = m;
    }

    public void addBeforeCompletionDescriptor(MethodDescriptor m) {
        beforeCompletionMethod = m;
    }

    public void addAfterCompletionDescriptor(MethodDescriptor m) {
        afterCompletionMethod = m;
    }

    /**
     * Set the Method annotated @AfterBegin.
     */
    public void setAfterBeginMethodIfNotSet(MethodDescriptor m) {
        if( afterBeginMethod == null) {
            afterBeginMethod = m;
        }
    }
    
    /**
     * Returns the Method annotated @AfterBegin.
     */
    public MethodDescriptor getAfterBeginMethod() {
        return afterBeginMethod;
    }
    
    /**
     * Set the Method annotated @BeforeCompletion.
     */
    public void setBeforeCompletionMethodIfNotSet(MethodDescriptor m) {
        if( beforeCompletionMethod == null ) {
            beforeCompletionMethod = m;
        }
    }
    
    /**
     * Returns the Method annotated @AfterBegin.
     */
    public MethodDescriptor getBeforeCompletionMethod() {
        return beforeCompletionMethod;
    }
    
    /**
     * Set the Method annotated @AfterCompletion.
     */
    public void setAfterCompletionMethodIfNotSet(MethodDescriptor m) {
        if( afterCompletionMethod == null ) {
            afterCompletionMethod = m;
        }
    }
    
    /**
     * Returns the Method annotated @AfterCompletion.
     */
    public MethodDescriptor getAfterCompletionMethod() {
        return afterCompletionMethod;
    }


    public boolean getInitOnStartup() {
        return ( (initOnStartup != null) && initOnStartup );
    }

    public void setInitOnStartup(boolean flag) {
        initOnStartup = new Boolean(flag);
    }

    public void setInitOnStartupIfNotAlreadySet(boolean flag) {
        if( initOnStartup == null ) {
            setInitOnStartup(flag);
        }
    }

    public String[] getDependsOn() {
        return dependsOn;
    }

    public boolean hasDependsOn() {
        return (dependsOn.length > 0);
    }

    public void setDependsOn(String[] dep) {
        dependsOn = (dep == null) ? _emptyDepends : dep;
    }

    public void setDependsOnIfNotSet(String[] dep) {
        if( !hasDependsOn() ) {
            setDependsOn(dep);
        }
    }

    public ConcurrencyManagementType getConcurrencyManagementType() {
        return (concurrencyManagementType != null) ? concurrencyManagementType :
                ConcurrencyManagementType.Container;
    }

    public boolean hasContainerManagedConcurrency() {
        return (getConcurrencyManagementType() == ConcurrencyManagementType.Container);
    }

    public boolean hasBeanManagedConcurrency() {
        return (getConcurrencyManagementType() == ConcurrencyManagementType.Bean);
    }

    public boolean isConcurrencyProhibited() {
        return (getConcurrencyManagementType() == ConcurrencyManagementType.NotAllowed);
    }   


    public void setConcurrencyManagementType(ConcurrencyManagementType type) {
        concurrencyManagementType = type;
    }

    public void setConcurrencyManagementTypeIfNotSet(ConcurrencyManagementType type) {
        if( concurrencyManagementType == null) {
            setConcurrencyManagementType(type);
        }
    }

    public void addConcurrentMethodFromXml(ConcurrentMethodDescriptor concMethod) {

        // .xml must contain a method.  However, both READ/WRITE lock metadata
        // and access timeout are optional.


        MethodDescriptor methodDesc = concMethod.getConcurrentMethod();

        if( concMethod.hasLockMetadata()) {

            if( concMethod.isWriteLocked()) {
                addWriteLockMethod(methodDesc);
            } else {
                addReadLockMethod(methodDesc);
            }
        }

        if( concMethod.hasAccessTimeout() ) {

            this.addAccessTimeoutMethod(methodDesc, concMethod.getAccessTimeoutValue(),
                    concMethod.getAccessTimeoutUnit());    
        }

    }

    public void addReadLockMethod(MethodDescriptor methodDescriptor) {
        readLockMethods.add(methodDescriptor);
    }

    public void addWriteLockMethod(MethodDescriptor methodDescriptor) {
        writeLockMethods.add(methodDescriptor);
    }

    public List<MethodDescriptor> getReadLockMethods() {
        return new ArrayList<MethodDescriptor>(readLockMethods);
    }

    public List<MethodDescriptor> getWriteLockMethods() {
        return new ArrayList<MethodDescriptor>(writeLockMethods);
    }

    public List<MethodDescriptor> getReadAndWriteLockMethods() {
        List<MethodDescriptor> readAndWriteLockMethods = new ArrayList<MethodDescriptor>();
        readAndWriteLockMethods.addAll(readLockMethods);
        readAndWriteLockMethods.addAll(writeLockMethods);
        return readAndWriteLockMethods;
    }

    public void addAccessTimeoutMethod(MethodDescriptor methodDescriptor, long value,
                                       TimeUnit unit) {
        accessTimeoutMethods.add(new AccessTimeoutHolder(value, unit, methodDescriptor));
    }

    public List<MethodDescriptor> getAccessTimeoutMethods() {
        List<MethodDescriptor> methods = new ArrayList<MethodDescriptor>();
        for(AccessTimeoutHolder holder : accessTimeoutMethods){
            methods.add(holder.method);
        }
        return methods;
    }

    public List<AccessTimeoutHolder> getAccessTimeoutInfo() {
        List<AccessTimeoutHolder> all = new ArrayList<AccessTimeoutHolder>();
        for(AccessTimeoutHolder holder : accessTimeoutMethods){
            all.add(holder);
        }
        return all;
    }


    public void processAsyncMethodsFromXml() {

         // Now we can do any asynchronous method processing from .xml
         for(MethodDescriptor next : tempAsyncMethodsFromXml) {
            setMatchingAsyncMethods(next);
         }

         // Clear out temporary list
         tempAsyncMethodsFromXml.clear();

    }

    private void setMatchingAsyncMethods(MethodDescriptor methodDescFromXml) {

        String methodIntfTag = methodDescFromXml.getEjbClassSymbol();

        Method methodFromXml = methodDescFromXml.getMethod(this);

        if( methodFromXml == null ) {
            throw new RuntimeException("Invalid Async method signature " + methodDescFromXml +
                " . Method could not be resolved to a bean class method for bean " + this.getName());
        }

        for(Object o : getClientBusinessMethodDescriptors()) {
            MethodDescriptor nextDesc = (MethodDescriptor) o;
            Method next = nextDesc.getMethod(this);

            if( TypeUtil.sameMethodSignature(methodFromXml, next)) {

                if( methodIntfTag == null ) {
                    nextDesc.setAsynchronous(true);
                } else if ( methodIntfTag.equals(nextDesc.getEjbClassSymbol()) ) {
                    nextDesc.setAsynchronous(true);
                }
            }

        }

    }

	/**
	* Returns a formatted String of the attributes of this object.
	*/
    public void print(StringBuffer toStringBuffer) {
	    toStringBuffer.append("Session descriptor");
	    toStringBuffer.append("\n sessionType ").append(getSessionType());
	    super.print(toStringBuffer);
    }

    public static class AccessTimeoutHolder {
        public AccessTimeoutHolder(long v, TimeUnit u, MethodDescriptor m) {
            value = v;
            unit = u;
            method = m;
        }
        public long value;
        public TimeUnit unit;
        public MethodDescriptor method;
    }

    public enum ConcurrencyManagementType {
        Bean,
        Container,
        NotAllowed
    }
}
