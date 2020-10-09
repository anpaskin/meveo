/*
 * (C) Copyright 2018-2019 Webdrone SAS (https://www.webdrone.fr) and contributors.
 * (C) Copyright 2015-2018 Opencell SAS (http://opencellsoft.com/) and contributors.
 * (C) Copyright 2009-2014 Manaty SARL (http://manaty.net/) and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * This program is not suitable for any direct or indirect application in MILITARY industry
 * See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.meveo.service.script;

import static org.meveo.model.scripts.ScriptSourceTypeEnum.JAVA;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.persistence.FlushModeType;
import javax.persistence.NoResultException;
import javax.persistence.Query;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.meveo.admin.exception.BusinessException;
import org.meveo.admin.exception.ElementNotFoundException;
import org.meveo.admin.exception.InvalidScriptException;
import org.meveo.admin.util.ResourceBundle;
import org.meveo.cache.CacheKeyStr;
import org.meveo.commons.utils.FileUtils;
import org.meveo.commons.utils.MeveoFileUtils;
import org.meveo.commons.utils.ReflectionUtils;
import org.meveo.commons.utils.StringUtils;
import org.meveo.event.qualifier.Removed;
import org.meveo.event.qualifier.git.CommitEvent;
import org.meveo.event.qualifier.git.CommitReceived;
import org.meveo.model.customEntities.CustomEntityInstance;
import org.meveo.model.git.GitRepository;
import org.meveo.model.persistence.CEIUtils;
import org.meveo.model.persistence.JacksonUtil;
import org.meveo.model.scripts.Accessor;
import org.meveo.model.scripts.CustomScript;
import org.meveo.model.scripts.FunctionIO;
import org.meveo.model.scripts.MavenDependency;
import org.meveo.model.scripts.ScriptInstance;
import org.meveo.model.scripts.ScriptInstanceError;
import org.meveo.model.scripts.ScriptSourceTypeEnum;
import org.meveo.model.scripts.test.ExpectedOutput;
import org.meveo.security.CurrentUser;
import org.meveo.security.MeveoUser;
import org.meveo.service.config.impl.MavenConfigurationService;
import org.meveo.service.git.GitClient;
import org.meveo.service.git.GitHelper;
import org.meveo.service.git.MeveoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

/**
 * @param <T> Script sub-type
 * @author Edward P. Legaspi | czetsuya@gmail.com
 * @version 6.11
 */
public abstract class CustomScriptService<T extends CustomScript> extends FunctionService<T, ScriptInterface> {

    private static final Map<CacheKeyStr, ScriptInterfaceSupplier> ALL_SCRIPT_INTERFACES = new ConcurrentHashMap<>();
    
    /** Class path used to compile scripts */
    public static final AtomicReference<String> CLASSPATH_REFERENCE = new AtomicReference<>("");
    
    private static Logger staticLogger = LoggerFactory.getLogger(CustomScriptService.class);

    @Inject
    private ResourceBundle resourceMessages;

    @Inject
    @CurrentUser
    protected MeveoUser currentUser;

    @Inject
    @MeveoRepository
    private GitRepository meveoRepository;

    @Inject
    private GitClient gitClient;

    @Inject
    private MavenConfigurationService mavenConfigurationService;
    
    private RepositorySystem defaultRepositorySystem;

    private RepositorySystemSession defaultRepositorySystemSession;
    
    @PostConstruct
    private void init() {
        if(mavenConfigurationService.getM2FolderPath() != null) {
            defaultRepositorySystem = mavenConfigurationService.newRepositorySystem();
            defaultRepositorySystemSession = mavenConfigurationService.newRepositorySystemSession(defaultRepositorySystem);
        }
    }

    /**
     * Constructor.
     */
    public CustomScriptService() {

        super();
    }

    /**
     * Find scripts by source type.
     *
     * @param type script source type
     * @return list of scripts
     */
    @SuppressWarnings("unchecked")
    public List<T> findByType(ScriptSourceTypeEnum type) {
        List<T> result = new ArrayList<>();
        try {
            result = (List<T>) getEntityManager().createNamedQuery("CustomScript.getScriptInstanceByTypeActive").setParameter("sourceTypeEnum", type).getResultList();
        } catch (NoResultException ignored) {

        }
        return result;
    }

    /**
     * If script file has been changed, commit the differences. <br>
     * Re-compile the script
     *
     * @param script Created or updated {@link CustomScript}
     */
    @Override
    protected void afterUpdateOrCreate(T script) {
    	
        try {
        	boolean commitFile = true;
            File scriptFile = findScriptFile(script);
            if (scriptFile.exists()) {
                String previousScript = MeveoFileUtils.readString(scriptFile.getAbsolutePath());
                if (previousScript.equals(script.getScript())) {
                    // Don't commit if there are no difference
                	commitFile = false;
                }
            }

            if(commitFile) {
	            buildScriptFile(scriptFile, script);
	            gitClient.commitFiles(meveoRepository, Collections.singletonList(scriptFile), "Create or update script " + script.getCode());
            }
            
        } catch (Exception e) {
            log.error("Error committing script", e);
        }

        compileScript(script, false);
    }

    @Override
    protected void validate(T script) throws BusinessException {
        if (script.getSourceTypeEnum() == ScriptSourceTypeEnum.ES5) {
            return;
        }
        String className = getClassName(script.getScript());
        if (className == null) {
            throw new BusinessException(resourceMessages.getString("message.scriptInstance.sourceInvalid"));
        }
        String fullClassName = getFullClassname(script.getScript());
        if (isOverwritesJavaClass(fullClassName)) {
            throw new BusinessException(resourceMessages.getString("message.scriptInstance.classInvalid", fullClassName));
        }
    }

    @Override
    protected String getCode(T script) {
        if (script.getSourceTypeEnum() == JAVA) {
            return getFullClassname(script.getScript());
        } else {
            return script.getCode();
        }
    }

    @Override
    public ScriptInterface getExecutionEngine(String scriptCode, Map<String, Object> context) {
        T script = this.findByCode(scriptCode);
        detach(script);
        return getExecutionEngine(script, context);
    }

    @Override
    public ScriptInterface getExecutionEngine(T script, Map<String, Object> context) {
        try {
            ScriptInterface scriptInstance = this.getScriptInstance(script.getCode());

            // Call setters if those are provided
            if (script.getSourceTypeEnum() == ScriptSourceTypeEnum.JAVA && context != null) {
                for (Accessor setter : script.getSettersNullSafe()) {
                    Object setterValue = context.get(setter.getName());
                    if (setterValue != null) {
                        // In case the parameters are initialized by a get request, we might need to
                        // convert the input to their right types
                        ScriptUtils.ClassAndValue classAndValue = new ScriptUtils.ClassAndValue();
                        if (!setter.getType().equals("String") && setterValue instanceof String) {
                            classAndValue = ScriptUtils.findTypeAndConvert(setter.getType(), (String) setterValue);
                        } else {
                            classAndValue.setValue(setterValue);
                            classAndValue.setClass(setterValue.getClass());
                        }

                        Method method = ReflectionUtils.getSetterByNameAndSimpleClassName(scriptInstance.getClass(), setter.getMethodName(), setter.getType())
                        		.orElse(null);
                        
                        if(method.getParameters()[0].getType() != classAndValue.getTypeClass()) {
                        	// If value is a map or a custom entity instance, convert into target class
                        	if(classAndValue.getValue() instanceof Map) {
                        		Object convertedValue = JacksonUtil.convert(classAndValue.getValue(), method.getParameters()[0].getType());
                            	method.invoke(scriptInstance, convertedValue);
                        	} else if(classAndValue.getValue() instanceof CustomEntityInstance) {
                        		CustomEntityInstance cei = (CustomEntityInstance) classAndValue.getValue();
                        		Object convertedValue = CEIUtils.ceiToPojo(cei, method.getParameters()[0].getType());
                            	method.invoke(scriptInstance, convertedValue);
                        	} else {
                        		throw new IllegalArgumentException("Can't invoke setter " + method.getName() + " with value of type " + classAndValue.getValue().getClass().getName());
                        	}
                        	
                        } else {
                        	method.invoke(scriptInstance, classAndValue.getValue());
                        }
                        
                    }
                }
            }
            return scriptInstance;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected Map<String, Object> buildResultMap(ScriptInterface engine, Map<String, Object> context) {
        CustomScript script = this.findByCode(engine.getClass().getName());

        if (script == null) {
            // The script is probably not a Java script and we cannot retrieve its code
            // using its class name
            return context;
        }

        // Put getters' values to context
        if (script.getSourceTypeEnum() == ScriptSourceTypeEnum.JAVA) {
            for (Accessor getter : script.getGettersNullSafe()) {
                try {
                    Object getterValue = engine.getClass().getMethod(getter.getMethodName()).invoke(engine);
                    context.put(getter.getName(), getterValue);
                } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        return super.buildResultMap(engine, context);
    }

    /**
     * Check full class name is existed class path or not.
     *
     * @param fullClassName full class name
     * @return true i class is overridden
     */
    public static boolean isOverwritesJavaClass(String fullClassName) {
        try {
            Class.forName(fullClassName);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
    
    private static void addJarsToClassPath(File directory) {
        for (File file : directory.listFiles()) {
            if (file.getName().endsWith(".jar")) {
				try {
					String canonicalPath = file.getCanonicalPath();
					String classPath = CLASSPATH_REFERENCE.get();
					classPath += canonicalPath + File.pathSeparator;
					CLASSPATH_REFERENCE.set(classPath);
					
				} catch (IOException e) {
					staticLogger.warn("Can't add jar file to class path", e);
				}
				
            } else if(file.isDirectory()) {
            	addJarsToClassPath(file);
            }
        }
    }

    /**
     * Construct classpath for script compilation
     * @throws IOException if a file can't be read
     */
    public static void constructClassPath() throws IOException {
        if (CLASSPATH_REFERENCE.get().length() == 0) {
            synchronized (CLASSPATH_REFERENCE) {
                if (CLASSPATH_REFERENCE.get().length() == 0) {
                    String classpath = CLASSPATH_REFERENCE.get();

                    // Check if deploying an exploded archive or a compressed file
                    String thisClassfile = new Object() {}.getClass().getProtectionDomain().getCodeSource().getLocation().getFile();

                    // Handle wildfly modules
                    
                    
                    File realFile = new File(thisClassfile);

                    // Was deployed as exploded archive
                    if (realFile.exists()) {
                        File deploymentDir = realFile.getParentFile();
                        System.out.println("Deployment dir: " + deploymentDir.getAbsolutePath());
                        File[] files = deploymentDir.listFiles();
                        if (files != null) {
                            for (File file : files) {
                                if (file.getName().endsWith(".jar")) {
                                    classpath += file.getCanonicalPath() + File.pathSeparator;
                                }
                            }
                        }

                        // War was deployed as compressed archive
                    } else {

                        org.jboss.vfs.VirtualFile vFile = org.jboss.vfs.VFS.getChild(thisClassfile);
                        realFile = new File(org.jboss.vfs.VFSUtils.getPhysicalURI(vFile).getPath());

                        File deploymentDir = realFile.getParentFile().getParentFile();

                        Set<String> classPathEntries = new HashSet<>();

                        for (File physicalLibDir : Objects.requireNonNull(deploymentDir.listFiles())) {
                            if (physicalLibDir.isDirectory()) {
                                for (File f : FileUtils.getFilesToProcess(physicalLibDir, "*", "jar")) {
                                    classPathEntries.add(f.getCanonicalPath());
                                }
                            }
                        }

                        /* Fallback when thorntail is used */
                        if (classPathEntries.isEmpty()) {
                            for (File physicalLibDir : Objects.requireNonNull(deploymentDir.listFiles())) {
                                if (physicalLibDir.isDirectory()) {
                                    for (File subLib : Objects.requireNonNull(physicalLibDir.listFiles())) {
                                        if (subLib.isDirectory()) {
                                            final List<String> jars = FileUtils.getFilesToProcess(subLib, "*", "jar").stream().map(item->CustomScriptService.getFilePath(item)).collect(Collectors.toList());
                                            classPathEntries.addAll(jars);
                                            if (subLib.getName().equals("classes")) {
                                                classPathEntries.add(subLib.getCanonicalPath());
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // vfs used by thorntail
                            File vfsDir = deploymentDir.getParentFile().getParentFile();
                            for (File tempDir : Objects.requireNonNull(vfsDir.listFiles((dir, name) -> name.contains("temp")))) {
                                if (!tempDir.isDirectory()) {
                                    continue;
                                }

                                for (File subTempDir : Objects.requireNonNull(tempDir.listFiles((dir, name) -> name.contains("temp")))) {
                                    if (!subTempDir.isDirectory()) {
                                        continue;
                                    }

                                    for (File warDir : Objects.requireNonNull(subTempDir.listFiles((dir, name) -> name.contains(".war")))) {
                                        if (!warDir.isDirectory()) {
                                            continue;
                                        }

                                        for (File webInfDir : Objects.requireNonNull(warDir.listFiles((dir, name) -> name.equals("WEB-INF")))) {
                                            if (!webInfDir.isDirectory()) {
                                                continue;
                                            }

                                            for (File classesDir : Objects.requireNonNull(webInfDir.listFiles((dir, name) -> name.equals("classes")))) {
                                                if (classesDir.isDirectory()) {
                                                    classPathEntries.add(classesDir.getCanonicalPath());
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        classpath = String.join(File.pathSeparator, classPathEntries);

                    }
                    
                    CLASSPATH_REFERENCE.set(classpath);
                    
                	String cpt = System.getProperty("java.class.path");
                	File wildflyFolder = new File(cpt).getParentFile();
                	File moduleFolder = new File(wildflyFolder, "modules/system/layers/base");
                	addJarsToClassPath(moduleFolder);
                }
            }
       }
            
        System.out.println("Final classpath : " + CLASSPATH_REFERENCE.get());
    }

    private void checkEndpoints(CustomScript scriptInstance, List<Accessor> setters) throws BusinessException {

        // Check if endpoints parameters are not bound to deleted inputs
        final List<String> newInputs = setters.stream().map(Accessor::getName).collect(Collectors.toList());

        List<String> deletedProperties = scriptInstance.getInputs().stream().map(FunctionIO::getName).collect(Collectors.toList());

        deletedProperties.removeAll(newInputs);
    }

    private static String getFilePath(File jar) {
        try {
            return jar.getCanonicalPath();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Build the classpath and compile all scripts.
     *
     * @param scripts list of scripts
     */
    protected void compile(List<T> scripts) {
        try {

            constructClassPath();

            for (T script : scripts) {
                compileScript(script, false);
            }
        } catch (Exception e) {
            log.error("", e);
        }
    }

    /**
     * @param scriptCode code of the script to re-compile
     */
    public void refreshCompiledScript(String scriptCode) {

        T script = findByCode(scriptCode);
        if (script == null) {
            clearCompiledScripts(scriptCode);
        } else {
            compileScript(script, false);
        }

    }

    /**
     * Compile script, a and update script entity status with compilation errors.
     * Successfully compiled script is added to a compiled script cache if active
     * and not in test compilation mode.
     *
     * @param script      Script entity to compile
     * @param testCompile Is it a compilation for testing purpose. Won't clear nor
     *                    overwrite existing compiled script cache.
     */
    @Override
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void compileScript(T script, boolean testCompile) {
    	
    	final String source;
        if (testCompile || !findScriptFile(script).exists()) {
            source = script.getScript();
        } else {
            source = readScriptFile(script);
        }

        addScriptDependencies(script);

        List<ScriptInstanceError> scriptErrors = compileScript(script.getCode(), script.getSourceTypeEnum(), source, script.isActive(), testCompile);

        script.setError(scriptErrors != null && !scriptErrors.isEmpty());
        script.setScriptErrors(scriptErrors);
    }

    private void addScriptDependencies(T script) {

		if (script instanceof ScriptInstance) {
			ScriptInstance scriptInstance = (ScriptInstance) script;

			if (scriptInstance.getMavenDependencies() != null && scriptInstance.getMavenDependencies().size() > 0) {
				Set<String> mavenDependencies = getMavenDependencies(scriptInstance.getMavenDependenciesNullSafe());

				synchronized (CLASSPATH_REFERENCE) {
					mavenDependencies.stream().forEach(location -> {
						if (!StringUtils.isBlank(location) && !CLASSPATH_REFERENCE.get().contains(location)) {
							addLibrary(location);
							CLASSPATH_REFERENCE.set(CLASSPATH_REFERENCE.get() + File.pathSeparator + location);
						}
					});
				}
			}
		}
    }

	private Set<String> getMavenDependencies(Set<MavenDependency> mavenDependencies) {

		Set<String> result = new HashSet<>();
		List<String> repos = mavenConfigurationService.getMavenRepositories();
		String m2FolderPath = mavenConfigurationService.getM2FolderPath();

		if (!StringUtils.isBlank(m2FolderPath)) {
			String repoId = RandomStringUtils.randomAlphabetic(5);
			List<RemoteRepository> remoteRepositories = repos.stream()
					.map(e -> new RemoteRepository.Builder(repoId, "default", e)
					.build())
					.collect(Collectors.toList());

			log.debug("found {} repositories", remoteRepositories.size());

			if (mavenDependencies != null && !mavenDependencies.isEmpty()) {
				Set<ArtifactResult> artifacts = mavenDependencies.stream().map(e -> {
					DefaultArtifact rootArtifact = new DefaultArtifact(e.getGroupId(), e.getArtifactId(), e.getClassifier(), "jar", e.getVersion());
					DependencyFilter classpathFlter = DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE);
					Dependency root = new Dependency(rootArtifact, JavaScopes.COMPILE);

					try {
						// check if artifact exists in local repository or in maven central
						CollectRequest collectRequestLocal = new CollectRequest();
						collectRequestLocal.setRoot(root);

						DependencyRequest theDependencyRequest = new DependencyRequest(collectRequestLocal, classpathFlter);
						DependencyResult dependencyResult = defaultRepositorySystem.resolveDependencies(defaultRepositorySystemSession, theDependencyRequest);
						
						return dependencyResult.getArtifactResults();

					} catch (DependencyResolutionException e1) {
						
						// If local repository resolution failed, try with remote
						log.info("Local dependencies resolution failed ({}), trying with remote resolution", e1.getMessage());
						try {
							CollectRequest collectRequestRemote = new CollectRequest();
							collectRequestRemote.setRoot(root);
							remoteRepositories.forEach(collectRequestRemote::addRepository);
							
							DependencyRequest theDependencyRequest = new DependencyRequest(collectRequestRemote, classpathFlter);
							DependencyResult dependencyResult = defaultRepositorySystem.resolveDependencies(defaultRepositorySystemSession, theDependencyRequest);
							return dependencyResult.getArtifactResults();
							
						} catch (DependencyResolutionException e2) {
							log.error("Fail downloading dependencies {}", e1);
							return null; // TODO handle it
						}

					}
				}).filter(Objects::nonNull)
				.flatMap(Collection::stream)
				.collect(Collectors.toSet());

				log.debug("found {} artifacts", artifacts.size());

				result = artifacts.stream().filter(Objects::nonNull).map(e -> {
					return e.getArtifact().getFile().getPath();
				}).collect(Collectors.toSet());
			}
		}

		return result;
	}

    /**
     * Parse the java source code and extract getters and setters
     *
     * @param script Script to parse
     */
    @Override
    protected void beforeUpdateOrCreate(T script) throws BusinessException {
        if (script.getSourceTypeEnum() != ScriptSourceTypeEnum.JAVA) {
            return;
        }

        CompilationUnit compilationUnit;

        try {
            compilationUnit = JavaParser.parse(script.getScript());
        } catch (Exception e) {
            // Skip getter and setters parsing. We don't need to log errors as they will be
            // logged later in code.
            return;
        }

        final ClassOrInterfaceDeclaration classOrInterfaceDeclaration = compilationUnit.getChildNodes().stream().filter(e -> e instanceof ClassOrInterfaceDeclaration)
                .map(e -> (ClassOrInterfaceDeclaration) e)
                .findFirst()
                .get();

        final List<MethodDeclaration> methods = classOrInterfaceDeclaration.getMembers()
        		.stream().filter(e -> e instanceof MethodDeclaration)
        		.map(e -> (MethodDeclaration) e)
                .collect(Collectors.toList());

        final List<Accessor> setters = ScriptUtils.getSetters(methods);

        final List<Accessor> getters = ScriptUtils.getGetters(methods);
        
        // Find getter and setter defined in super types
        for(ClassOrInterfaceType type : classOrInterfaceDeclaration.getExtendedTypes()) {
        	Class<?> typeClass = null;
        	
        	try {
        		typeClass = Class.forName(type.toString());
        	} catch (Exception e) {
            	String className = compilationUnit.getImports().stream()
                		.filter(importEntry -> importEntry.getNameAsString().endsWith("." + type.getNameAsString()))
                		.map(ImportDeclaration::getNameAsString)
                		.findFirst()
                		.orElseThrow(() -> new RuntimeException("No declaration found for extended type " + type.getNameAsString()));
            	
            	try {
    				typeClass = Class.forName(className);
    			} catch (ClassNotFoundException e1) {
    				try {
    					typeClass = getScriptInterfaceWCompile(className).getScriptInterface().getClass();
    				} catch (Exception e2) {
    					throw new BusinessException(e2);
    				}
				}
        	}
				
			// Build getters and setter of extended types
			Arrays.stream(typeClass.getMethods())
					.filter(m -> m.getName().startsWith(Accessor.SET))
					.filter(m -> m.getParameterCount() == 1)
					.filter(m -> m.getReturnType() == void.class)
					.map(Accessor::new)
					.forEach(setters::add);
			
			Arrays.stream(typeClass.getMethods())
					.filter(m -> m.getName().startsWith(Accessor.GET) && !m.getName().equals("getClass"))
					.filter(m -> m.getParameterCount() == 0)
					.filter(m -> m.getReturnType() != void.class)
					.map(Accessor::new)
					.forEach(getters::add);
        }

        checkEndpoints(script, setters);

        script.setGetters(getters);
        script.setSetters(setters);
    }

	/**
     * Compile script. DOES NOT update script entity status. Successfully compiled
     * script is added to a compiled script cache if active and not in test
     * compilation mode.
     *
     * @param scriptCode  Script entity code
     * @param sourceType  Source code language type
     * @param sourceCode  Source code
     * @param isActive    Is script active. It will compile it anyway. Will clear
     *                    but not overwrite existing compiled script cache.
     * @param testCompile Is it a compilation for testing purpose. Won't clear nor
     *                    overwrite existing compiled script cache.
     * @return A list of compilation errors if not compiled
     */
    private List<ScriptInstanceError> compileScript(String scriptCode, ScriptSourceTypeEnum sourceType, String sourceCode, boolean isActive, boolean testCompile) {
        if (sourceType == ScriptSourceTypeEnum.JAVA) {
            log.debug("Compile script {}", scriptCode);

            try {
                if (!testCompile) {
                    clearCompiledScripts(scriptCode);
                }

                Class<ScriptInterface> compiledScript = compileJavaSource(sourceCode);

                if (!testCompile && isActive) {

                    ALL_SCRIPT_INTERFACES.put(new CacheKeyStr(currentUser.getProviderCode(), scriptCode), compiledScript.getDeclaredConstructor()::newInstance);
                    log.debug("Compiled script {} added to compiled interface map", scriptCode);
                }

                return null;

            } catch (CharSequenceCompilerException e) {
                log.error("Failed to compile script {}. Compilation errors:", scriptCode);

                List<ScriptInstanceError> scriptErrors = new ArrayList<>();

                List<Diagnostic<? extends JavaFileObject>> diagnosticList = e.getDiagnostics().getDiagnostics();
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnosticList) {
                    if ("ERROR".equals(diagnostic.getKind().name())) {
                        ScriptInstanceError scriptInstanceError = new ScriptInstanceError();
                        scriptInstanceError.setMessage(diagnostic.getMessage(Locale.getDefault()));
                        scriptInstanceError.setLineNumber(diagnostic.getLineNumber());
                        scriptInstanceError.setColumnNumber(diagnostic.getColumnNumber());
                        scriptInstanceError.setSourceFile(diagnostic.getSource().toString());
                        // scriptInstanceError.setScript(scriptInstance);
                        scriptErrors.add(scriptInstanceError);
                        // scriptInstanceErrorService.create(scriptInstanceError,
                        // scriptInstance.getAuditable().getCreator());
                        log.warn("{} script {} location {}:{}: {}", diagnostic.getKind().name(), scriptCode, diagnostic.getLineNumber(), diagnostic.getColumnNumber(),
                                diagnostic.getMessage(Locale.getDefault()));
                    }
                }
                return scriptErrors;

            } catch (Exception e) {
                log.error("Failed while compiling script", e);
                List<ScriptInstanceError> scriptErrors = new ArrayList<>();
                ScriptInstanceError scriptInstanceError = new ScriptInstanceError();
                scriptInstanceError.setMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                scriptErrors.add(scriptInstanceError);

                return scriptErrors;
            }
        } else {
            ScriptInterface engine = new ES5ScriptEngine(sourceCode);
            ALL_SCRIPT_INTERFACES.put(new CacheKeyStr(currentUser.getProviderCode(), scriptCode), () -> engine);
            return null;
        }
    }

    /**
     * Compile java Source script
     *
     * @param javaSrc Java source to compile
     * @return Compiled class instance
     * @throws CharSequenceCompilerException char sequence compiler exception.
     */
    protected Class<ScriptInterface> compileJavaSource(String javaSrc) throws CharSequenceCompilerException, IOException {
        String fullClassName = getFullClassname(javaSrc);
        String classPath = CLASSPATH_REFERENCE.get();
        CharSequenceCompiler<ScriptInterface> compiler = new CharSequenceCompiler<>(this.getClass().getClassLoader(), Arrays.asList("-cp", classPath));
        final DiagnosticCollector<JavaFileObject> errs = new DiagnosticCollector<>();
        return compiler.compile(fullClassName, javaSrc, errs, ScriptInterface.class);
    }

    /**
     * Find the script class for a given script code
     *
     * @param scriptCode Script code
     * @return Script interface Class
     * @throws Exception if error occurs
     */
    public synchronized ScriptInterface getScriptInterface(String scriptCode) throws Exception {
        ScriptInterfaceSupplier supplier = ALL_SCRIPT_INTERFACES.get(new CacheKeyStr(currentUser.getProviderCode(), scriptCode));

        if (supplier == null) {
            supplier = getScriptInterfaceWCompile(scriptCode);
        }

        return supplier.getScriptInterface();
    }

    /**
     * Compile the script class for a given script code if it is not compile yet.
     * NOTE: method is executed synchronously due to WRITE lock. DO NOT CHANGE IT,
     * so there would be only one attempt to compile a new script class
     *
     * @param scriptCode Script code
     * @return Script interface Class
     * @throws InvalidScriptException   Were not able to instantiate or compile a
     *                                  script
     * @throws ElementNotFoundException Script not found
     */
    protected ScriptInterfaceSupplier getScriptInterfaceWCompile(String scriptCode) throws ElementNotFoundException, InvalidScriptException {
        ScriptInterfaceSupplier result;

        result = ALL_SCRIPT_INTERFACES.get(new CacheKeyStr(currentUser.getProviderCode(), scriptCode));

        if (result == null) {
        	List<String> fetchFields = Arrays.asList("mavenDependencies");
            T script = findByCode(scriptCode, fetchFields);
            if (script == null) {
                log.debug("ScriptInstance with {} does not exist", scriptCode);
                throw new ElementNotFoundException(scriptCode, getEntityClass().getName());
            }
            
            compileScript(script, false);
            if (script.isError()) {
                log.debug("ScriptInstance {} failed to compile. Errors: {}", scriptCode, script.getScriptErrors());
                throw new InvalidScriptException(scriptCode, getEntityClass().getName());
            }
            
            result = ALL_SCRIPT_INTERFACES.get(new CacheKeyStr(currentUser.getProviderCode(), scriptCode));
            detach(script);
        }

        if (result == null) {
            log.debug("ScriptInstance with {} does not exist", scriptCode);
            throw new ElementNotFoundException(scriptCode, getEntityClass().getName());
        }

        return result;
    }

    /**
     * Get a compiled script class
     *
     * @param scriptCode Script code
     * @return A compiled script class
     * @throws InvalidScriptException Were not able to instantiate or compile a
     *                                script
     */
    public synchronized ScriptInterface getScriptInstance(String scriptCode) throws InvalidScriptException {
        try {
            return getScriptInterface(scriptCode);
        } catch (Exception e) {
            log.error("Failed to instantiate script {}", scriptCode, e);
            throw new InvalidScriptException(scriptCode, getEntityClass().getName());
        }
    }

    /**
     * Find the package name in a source java text.
     *
     * @param src Java source code
     * @return Package name
     */
    public static String getPackageName(String src) {
        return StringUtils.patternMacher("package (.*?);", src);
    }

    /**
     * Find the class name in a source java text
     *
     * @param src Java source code
     * @return Class name
     */
    public static String getClassName(String src) {
        String className = StringUtils.patternMacher("public class (.*) extends", src);
        if (className == null) {
            className = StringUtils.patternMacher("public class (.*) implements", src);
        }
        return className != null ? className.trim() : null;
    }

    /**
     * Gets a full classname of a script by combining a package (if applicable) and
     * a classname
     *
     * @param script Java source code
     * @return Full classname
     */
    public static String getFullClassname(String script) {
        String packageName = getPackageName(script);
        String className = getClassName(script);
        return (packageName != null ? packageName.trim() + "." : "") + className;
    }

    /**
     * Remove compiled script, its logs and cached instances for given script code
     *
     * @param scriptCode Script code
     */
    public void clearCompiledScripts(String scriptCode) {
        super.clear(scriptCode);
        ALL_SCRIPT_INTERFACES.remove(new CacheKeyStr(currentUser.getProviderCode(), scriptCode));
    }

    /**
     * Clear the script cache
     */
    public void clearCompiledScripts() {
        ALL_SCRIPT_INTERFACES.clear();
    }

    @Override
    public List<ExpectedOutput> compareResults(List<ExpectedOutput> expectedOutputs, Map<String, Object> results) {
        return null;
    }

    /**
     * Add a jar file to application class path
     * 
     * @param location location of the jar file
     */
    public void addLibrary(String location) {
        File file = new File(location);
        
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
        
        if(systemClassLoader instanceof URLClassLoader) {
			URLClassLoader classLoader = (URLClassLoader) systemClassLoader;
	        Method method;
	
	        try {
	            URL url = file.toURI().toURL();
	            method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
	            method.setAccessible(true);
	            method.invoke(classLoader, url);
	
	        } catch (Exception e) {
	            throw new RuntimeException(e);
	        }
	        
        } else {
	        try {
	        	Method method = systemClassLoader.getClass().getDeclaredMethod("appendToClassPathForInstrumentation", String.class);
	            method.setAccessible(true);
	            method.invoke(systemClassLoader, file.getAbsolutePath());
	
	        } catch (Exception e) {
	        	log.warn("Libray {} not added to classpath", location);
	        	log.warn("Can't access system class loader class, please restart JVM with the following options : --add-opens java.base/jdk.internal.loader=ALL-UNNAMED --add-exports=java.base/jdk.internal.loader=ALL-UNNAMED");
	        }
        }
    }

    /**
     * When a script is deleted, remove the file from git repository
     *
     * @param scriptInstance Removed {@link ScriptInstance}
     * @throws BusinessException if the modifications can't be committed
     */
    public void onScriptRemoved(@Observes @Removed ScriptInstance scriptInstance) throws BusinessException {
        File file = findScriptFile(scriptInstance);
        if (file.exists()) {
            file.delete();
            gitClient.commitFiles(meveoRepository, Collections.singletonList(file), "Remove script " + scriptInstance.getCode());
        }
    }

    /**
     * When a commit concerning script is received :
     * <ul>
     * <li>If script file has been created, create the JPA entity</li>
     * <li>If script file has been modified, re-compile it</li>
     * <li>If script file has been deleted, remove the JPA entity</li>
     * </ul>
     * @param commitEvent the event
     * @throws BusinessException if the modifications can't be committed
     * @throws IOException if a file can't be accessed
     */
    @SuppressWarnings("unchecked")
    public void onScriptUploaded(@Observes @CommitReceived CommitEvent commitEvent) throws BusinessException, IOException {
        if (commitEvent.getGitRepository().getCode().equals(meveoRepository.getCode())) {
            for (String modifiedFile : commitEvent.getModifiedFiles()) {
                if (modifiedFile.startsWith("src/main/java") && !modifiedFile.contains("customEntities")) {
                    String scriptCode = modifiedFile.replaceAll("src/main/java/(.*)\\..*$", "$1").replaceAll("/", ".");
                    T script = findByCode(scriptCode);
                    File repositoryDir = GitHelper.getRepositoryDir(currentUser, commitEvent.getGitRepository().getCode());
                    File scriptFile = new File(repositoryDir, modifiedFile);

                    if (script == null && scriptFile.exists()) {
                        // Script has been created
                        CustomScript scriptInstance = new ScriptInstance();
                        scriptInstance.setCode(scriptCode);
                        String absolutePath = scriptFile.getAbsolutePath();
                        ScriptSourceTypeEnum scriptType = absolutePath.endsWith(".js") ? ScriptSourceTypeEnum.ES5 : JAVA;
                        scriptInstance.setSourceTypeEnum(scriptType);
                        scriptInstance.setScript(MeveoFileUtils.readString(absolutePath));
                        create((T) scriptInstance);

                    } else if (script != null && !scriptFile.exists()) {
                        // Script has been removed
                        remove(script);

                    } else if (script != null && scriptFile.exists()) {
                        // Scipt has been updated
                    	script.setScript(readScriptFile(script));
                        update(script);
                        compileScript(script, false);
                    }
                }
            }
        }
    }

    private void buildScriptFile(File scriptFile, CustomScript scriptInstance) throws IOException {
        if (!scriptFile.getParentFile().exists()) {
            scriptFile.getParentFile().mkdirs();
        }

        org.apache.commons.io.FileUtils.write(scriptFile, scriptInstance.getScript(), StandardCharsets.UTF_8);
    }

    private File findScriptFile(CustomScript scriptInstance) {
        final File scriptDir = GitHelper.getRepositoryDir(currentUser, meveoRepository.getCode() + "/src/main/java/");
        if (!scriptDir.exists()) {
            scriptDir.mkdirs();
        }

        String extension = scriptInstance.getSourceTypeEnum() == ScriptSourceTypeEnum.ES5 ? ".js" : ".java";
        String path = scriptInstance.getCode().replaceAll("\\.", "/");
        return new File(scriptDir, path + extension);
    }

    /**
     * @param script the script to get the file
     * @return the content of the script file
     */
    public String readScriptFile(CustomScript script) {
        File scriptFile = findScriptFile(script);

        try {
            return MeveoFileUtils.readString(scriptFile.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Return list of import scripts.
     *
     * @param javaSource java source
     * @return the script imported in the java source
     */
    public List<String> getImportScripts(String javaSource) {
        String regexImport = "import (.*?);";
        Pattern patternImport = Pattern.compile(regexImport);
        Matcher matcherImport = patternImport.matcher(javaSource);
        List<String> results = new ArrayList<>();
        while (matcherImport.find()) {
            String nameImport = matcherImport.group(1);
            results.add(nameImport);
        }
        return results;
    }

    /**
     * @param script the script content to analyze
     * @return the list of the scripts imported
     */
    public List<ScriptInstance> populateImportScriptInstance(String script) {
        List<String> importedScripts = getImportScripts(script);
        List<ScriptInstance> scriptInstances = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(importedScripts)) {
            for (String scriptCode : importedScripts) {
            	String query = "FROM ScriptInstance si WHERE si.code = :scriptCode";
            	Query q = getEntityManager().createQuery(query)
        			.setParameter("scriptCode", scriptCode)
            		.setFlushMode(FlushModeType.COMMIT);
            	
            	try {
	                ScriptInstance scriptInstance = (ScriptInstance) q.getSingleResult();
	                scriptInstances.add(scriptInstance);
            	} catch(NoResultException e) {}
            }
        }
        return scriptInstances;
    }
   
    /**
     * @return the script cache
     */
    public Map<CacheKeyStr, ScriptInterfaceSupplier> getScriptCache() {
    	return ALL_SCRIPT_INTERFACES;
    }
}
