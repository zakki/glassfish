/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2009 Sun Microsystems, Inc. All rights reserved.
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
package org.glassfish.appclient.server.core;

import com.sun.enterprise.deployment.ApplicationClientDescriptor;
import com.sun.enterprise.deployment.BundleDescriptor;
import com.sun.enterprise.deployment.archivist.AppClientArchivist;
import com.sun.enterprise.deployment.deploy.shared.InputJarArchive;
import com.sun.enterprise.deployment.deploy.shared.Util;
import com.sun.enterprise.deployment.util.ModuleDescriptor;
import com.sun.logging.LogDomains;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URI;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.glassfish.api.ActionReport;
import org.glassfish.api.deployment.DeployCommandParameters;
import org.glassfish.api.deployment.DeploymentContext;
import org.glassfish.api.deployment.archive.ReadableArchive;
import org.glassfish.deployment.common.DownloadableArtifacts;
import org.glassfish.deployment.common.DownloadableArtifacts.FullAndPartURIs;

class NestedAppClientDeployerHelper extends AppClientDeployerHelper {

    private Set<FullAndPartURIs> libraryAndClassPathJARs = new HashSet<FullAndPartURIs>();

    private StringBuilder classPathForFacade = new StringBuilder();

    private final URI earURI;

    private static Logger logger = LogDomains.getLogger(NestedAppClientDeployerHelper.class, LogDomains.ACC_LOGGER);

    /**
     * records the downloads needed to support this app client,
     * including the app client JAR itself, the facade, and the transitive
     * closure of any library JARs from the EAR's lib directory or from the
     * app client's class path
     */
    private final Set<FullAndPartURIs> downloads = new HashSet<FullAndPartURIs>();

    /** recognizes expanded directory names for submodules */
    private static final Pattern submoduleURIPattern = Pattern.compile("(.*)_([wcrj]ar)$");

    NestedAppClientDeployerHelper(
            final DeploymentContext dc,
            final ApplicationClientDescriptor bundleDesc,
            final AppClientArchivist archivist,
            final ClassLoader gfClientModuleClassLoader) throws IOException {
        super(dc, bundleDesc, archivist, gfClientModuleClassLoader);
        earURI = dc.getSource().getParentArchive().getURI();
        processDependencies();
    }

    private void processDependencies() throws IOException {

        /*
         * Init the class path for the facade so it refers to the developer's app client,
         * relative to where the facade will be.
         */
        URI appClientURI = URI.create(Util.getURIName(appClientUserURI(dc())));
        classPathForFacade.append(appClientURI);

        /*
         * For a nested app client, the required downloads include the
         * developer's original app client JAR, the generated facade JAR,
         * the generated EAR-level facade, and
         * the transitive closure of all JARs in the app client's Class-Path
         * and the JARs in the EAR's library-directory.
         *
         * Note that the EAR deployer will add the EAR-level facade as a download
         * for each of its submodule app clients.
         */
        downloads.add(new DownloadableArtifacts.FullAndPartURIs(
                facadeServerURI(dc()),
                facadeUserURI(dc())));

        /*
         * dependencyURIsProcessed records URIs, relative to the original JAR as it will
         * reside in the user's download directory, that have already been
         * processed.  This allows us to avoid processing the same JAR or dir more
         * than once if more than one JAR depends on it.
         */
        Set<URI> dependencyURIsProcessed = new HashSet<URI>();

        String appClientURIWithinEAR = appClientDesc().getModuleDescriptor().getArchiveUri();
        processDependencies(
                earURI,
                URI.create(appClientURIWithinEAR),
                downloads,
                dependencyURIsProcessed,
                appClientURI);

        /*
         * Now incorporate the library JARs.
         */
        final String libDir = appClientDesc().getApplication().getLibraryDirectory();
        if (libDir != null) {
            File libDirFile = new File(new File(earURI), libDir);
            if (libDirFile.exists() && libDirFile.isDirectory()) {
                for (File libJar : libDirFile.listFiles(new FileFilter() {
                    public boolean accept(File pathname) {
                        return pathname.getName().endsWith(".jar") && ! pathname.isDirectory();
                    }
                })) {
                    final URI libJarURI = facadeServerURI(dc()).relativize(libJar.toURI());
                    /*
                     * Add a relative URI from the facade to this library JAR
                     * to the class path.
                     */
                    classPathForFacade.append(' ').append(libJarURI.toASCIIString());

                    /*
                     * Process this library JAR to record the need to download it
                     * and any JARs it depends on.
                     */
                    URI jarURI = URI.create("jar:" + libJar.toURI().getRawSchemeSpecificPart());
                    processDependencies(earURI, jarURI, downloads, dependencyURIsProcessed,
                            libJarURI);
                }
            }
        }
    }

    /**
     * Processes a JAR URI on which the developer's app client depends, adding
     * the JAR to the set of JARs for download.
     * <p>
     * If the URI actually maps to an expanded directory for a submodule, this
     * method makes a copy of the submodule as a JAR so it will be available
     * after deployment has finished (which is not the case for uploaded EARs)
     * and can be downloaded as a JAR (which is not the case for submodules
     * in directory-deployed EARs.
     *
     * @param baseURI base against which to resolve the dependency URI (could be
     * the EAR's expansion URI, or could be the URI to a JAR containing a
     * Class-Path element, for example)
     * @param dependencyURI the JAR or directory entry representing a dependency
     * @param downloads the full set of items to be downloaded to support the current client
     * @param dependentURIsProcessed JAR and directory URIs already processed (so
     * we can avoid processing the same JAR or directory multiple times)
     * @throws java.io.IOException
     */
    private void processDependencies(
            final URI baseURI,
            final URI dependencyURI,
            final Set<FullAndPartURIs> downloads,
            final Set<URI> dependencyURIsProcessed,
            final URI containingJARURI) throws IOException {

        if (dependencyURIsProcessed.contains(dependencyURI)) {
            return;
        }

        /*
         * The dependencyURI could be a ghost one - meaning that the descriptor
         * specifies it as a module JAR but a directory deployment is in
         * progress so that JAR is actually an expanded directory.  In that case
         * we need to generate a JAR for download and build a FullAndPartURIs
         * object pointing to that generated JAR.
         */
        URI dependencyFileURI = baseURI.resolve(dependencyURI);

        /*
         * Make sure the URI has the scheme "file" and not "jar" because
         * dependencies from the Class-Path in a JAR's manifest could be
         * "jar" URIs.  We need "file" URIs to check for existence, etc.
         */

        String scheme = dependencyFileURI.getScheme();
        if (scheme != null && scheme.equals("jar")) {
            dependencyFileURI = URI.create("file:" + dependencyFileURI.getRawSchemeSpecificPart());
        } else {
            if (scheme == null) {
                scheme = "file";
            }
            dependencyFileURI = URI.create(scheme + ":" + dependencyFileURI.getRawSchemeSpecificPart());
        }

        File dependentFile = new File(dependencyFileURI);
        if ( ! dependentFile.exists()) {
            if (isSubmodule(dependencyURI)) {
                dependentFile = JAROfExpandedSubmodule(dependencyURI);
                dependencyFileURI = dependentFile.toURI();
            } else {
                /*
                 * A JAR's Class-Path could contain non-existent JARs.  If there
                 * is no JAR then no more needs to be done with this URI.
                 */
                return;
            }
        }

        /*
         * The app might specify non-existent JARs in its Class-Path.
         */
        if ( ! dependentFile.exists()) {
            return;
        }
        
        if (dependentFile.isDirectory() && ! isSubmodule(dependencyURI)) {
            /*
             * Make sure the dependencyURI (which would have come from a JAR's
             * Class-Path) for this directory ends with a slash.  Otherwise
             * the default system class loader, URLClassLoader, will NOT treat
             * it as a directory.
             */
            if (! dependencyURI.getPath().endsWith("/")) {
                final String format = logger.getResourceBundle().
                        getString("enterprise.deployment.appclient.dirURLnoSlash");
                final String msg = MessageFormat.format(format, dependencyURI.getPath(),
                            containingJARURI.toASCIIString());
                logger.log(Level.WARNING, msg);
                ActionReport warning = dc().getActionReport();
                warning.setMessage(msg);
                warning.setActionExitCode(ActionReport.ExitCode.WARNING);
            } else {
            /*
             * This is a directory.  Add all files within it to the set to be
             * downloaded but do not traverse the manifest Class-Path of any
             * contained JARs.
             */
            processDependentDirectory(dependentFile, baseURI, 
                    dependencyURIsProcessed, downloads);
            }
        } else {
            processDependentJAR(dependentFile, baseURI, 
                    dependencyURI, dependencyFileURI, dependencyURIsProcessed, 
                    downloads, containingJARURI);
        }
        
    }
    
    private void processDependentDirectory(
            final File dependentDirFile,
            final URI baseURI,
            final Set<URI> dependencyURIsProcessed,
            final Set<FullAndPartURIs> downloads) {
        
        /*
         * Iterate through this directory and its subdirectories, marking
         * each contained file for download.
         */
        for (File f : dependentDirFile.listFiles()) {
            if (f.isDirectory()) {
                processDependentDirectory(f, baseURI, dependencyURIsProcessed, downloads);
            } else {
                URI dependencyFileURI = f.toURI();
                DownloadableArtifacts.FullAndPartURIs fileDependency = new FullAndPartURIs(dependencyFileURI,
                    earDirUserURI(dc()).resolve(earURI.relativize(dependencyFileURI)));
                downloads.add(fileDependency);
            }
        }
    }
    
    private void processDependentJAR(final File dependentFile,
            final URI baseURI,
            final URI dependencyURI,
            final URI dependencyFileURI,
            final Set<URI> dependencyURIsProcessed,
            final Set<FullAndPartURIs> downloads,
            final URI containingJARURI
            ) throws IOException {

        /*
         * On the client we want the directory to look like this after the
         * download has completed (for example):
         *
         *   downloadDir/  (as specified on the deploy command)
         *      generated-dir-for-this-EAR's-artifacts/
         *         clientFacadeJAR.jar
         *         clientJAR.jar
         *         lib/lib1.jar
         *   ...
         *
         * The "part" portion of the FullAndPartURIs object needs to be the
         * path of the downloaded item relative to the downloadDir in the
         * layout above.
         *
         * To compute that:
         *
         * 1. We resolve the URI of the dependency against the base
         * URI first.  (The base URI will be the directory where
         * the EAR has been expanded for the app client JAR,
         * then might be other paths as we traverse Class-Path chains from the
         * manifests of various JARs we process).
         *
         * 2. Then relativize that against the directory on the
         * server where the EAR has been expanded.  That gives
         * us the relative path within this app's download directory on the
         * client.

         * 3. This app's download directory lies within the user-specified
         * download directory (from the command line).  So we relativize
         * the result so far once more, this time against the download
         * directory on the client system.
         */
        DownloadableArtifacts.FullAndPartURIs jarFileDependency = new FullAndPartURIs(dependencyFileURI,
                earDirUserURI(dc()).resolve(earURI.relativize(baseURI.resolve(dependencyURI))));

        downloads.add(jarFileDependency);

        /*
         * Process any JARs in this JAR's class path by opening it as an
         * archive and getting its manifest and processing the Class-Path
         * entry from there.
         */
        URI jarURI = URI.create("jar:" + dependencyFileURI.getRawSchemeSpecificPart());
        ReadableArchive dependentJar = new InputJarArchive();
        dependentJar.open(jarURI);

        Manifest jarManifest = dependentJar.getManifest();
        dependentJar.close();

        Attributes mainAttrs = jarManifest.getMainAttributes();

        String jarClassPath = mainAttrs.getValue(Attributes.Name.CLASS_PATH);
        if (jarClassPath != null) {
            for (String elt : jarClassPath.split(" ")) {
                /*
                 * A Class-Path list might have multiple spaces as a separator.
                 * Ignore empty elements.
                 */
                if (elt.trim().length() > 0) {
                    processDependencies(dependencyFileURI, URI.create(elt),
                            downloads, dependencyURIsProcessed,
                            containingJARURI);
                }
            }
        }
    }

    private boolean isSubmodule(final URI candidateURI) {
        for (ModuleDescriptor<BundleDescriptor> desc : appClientDesc().getApplication().getModules()) {
            if (URI.create(desc.getArchiveUri()).equals(candidateURI)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the candidate URI matches the given submodule URI.
     * Either URI could be to a directory, perhaps because the user has
     * directory-deployed the EAR or perhaps because the app server has expanded
     * submodule JARs into directories in the server's repository.
     *
     * @param candidateURI possible submodule URI
     * @param submoduleURIText submodule URI text to compare to
     * @return true if the candiateURI matches the submoduleURI, accounting for
     * either or both being directories; false otherwise
     */
    private boolean matchesSubmoduleURI(final URI candidateURI, final String submoduleURIText) {
        Matcher candidateMatcher = submoduleURIPattern.matcher(candidateURI.getPath());
        URI normalizedCandidateURI = (candidateMatcher.matches()
                ? URI.create(candidateMatcher.group(1) + "." + candidateMatcher.group(2))
                : candidateURI);
        candidateMatcher.reset(submoduleURIText);
        URI normalizedSubmoduleURI = (candidateMatcher.matches()
                ? URI.create(candidateMatcher.group(1) + "." + candidateMatcher.group(2))
                : URI.create(submoduleURIText));

        return normalizedCandidateURI.equals(normalizedSubmoduleURI);
    }

    private URI convertExpandedDirToJarURI(final String submoduleURI) {
        URI result = null;
        Matcher m = submoduleURIPattern.matcher(submoduleURI);
        if (m.matches()) {
            result = URI.create(m.group(1) + "." + m.group(2));
        }
        return result;
    }

    @Override
    protected URI facadeServerURI(DeploymentContext dc) {
        File genXMLDir = dc.getScratchDir("xml");
        return genXMLDir.toURI().resolve(relativeFacadeURI(dc));
    }

    @Override
    protected Set<FullAndPartURIs> downloads() {
        return downloads;
    }

    @Override
    protected String facadeClassPath() {
        return classPathForFacade.toString();
    }

    private String appName(final DeploymentContext dc) {
        DeployCommandParameters params = dc.getCommandParameters(DeployCommandParameters.class);
        return params.name();
    }

    @Override
    protected URI facadeUserURI(DeploymentContext dc) {
        return URI.create(appName(dc) + "Client/" + relativeFacadeURI(dc));
    }

    private URI relativeFacadeURI(DeploymentContext dc) {
        return moduleURI().resolve(facadeFileNameAndType(dc));
    }

    @Override
    protected String facadeFileNameAndType(DeploymentContext dc) {
        return moduleNameOnly() + "Client.jar";
    }

    @Override
    protected URI appClientUserURI(DeploymentContext dc) {
        return earDirUserURI(dc).resolve(moduleURI());
    }

    @Override
    protected URI appClientUserURIForFacade(DeploymentContext dc) {
        return URI.create(Util.getURIName(appClientUserURI(dc)));
    }


    private URI earDirUserURI(final DeploymentContext dc) {
        return URI.create(appName(dc) + "Client/");
    }

    @Override
    protected URI appClientServerURI(DeploymentContext dc) {
        URI result;
        String appClientURIWithinEAR = appClientDesc().getModuleDescriptor().getArchiveUri();
        Matcher m = submoduleURIPattern.matcher(appClientURIWithinEAR);
        final File userProvidedJarFile = new File(new File(earURI), appClientURIWithinEAR);
        /*
         * If either the URI specifies the expanded directory for a directory-
         * deployed app client or there is no actual JAR file for the app
         * client (meaning it is an expanded directory),
         * the server-side URI for the app client JAR will need to be in
         * the generated directory.
         */
        if (m.matches()) {
            result = new File(dc.getScratchDir("xml"), m.group(1) + "." + m.group(2)).toURI();
        } else if ( ! userProvidedJarFile.exists())  {
            result = new File(dc.getScratchDir("xml"), appClientURIWithinEAR).toURI();
        } else {
            result = userProvidedJarFile.toURI();
        }
        return result;
    }

    @Override
    protected URI appClientURIWithinApp(DeploymentContext dc) {
        return URI.create(appClientDesc().getModuleDescriptor().getArchiveUri());
    }

    private URI moduleURI() {
        return URI.create(appClientDesc().getModuleDescriptor().getArchiveUri());
    }

    private String moduleNameAndType() {
        return Util.getURIName(moduleURI());
    }

    private String moduleNameOnly() {
        String nameAndType = moduleNameAndType();
        return nameAndType.substring(0, nameAndType.lastIndexOf(".jar"));
    }
}
