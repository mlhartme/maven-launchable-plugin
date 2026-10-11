/*
 * Copyright 1&1 Internet AG, https://github.com/1and1/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.oneandone.maven.plugins.application;

import net.oneandone.sushi.fs.Node;
import net.oneandone.sushi.fs.World;
import net.oneandone.sushi.fs.file.FileNode;
import net.oneandone.sushi.util.Separator;
import net.oneandone.sushi.util.Strings;
import net.oneandone.sushi.util.Substitution;
import net.oneandone.sushi.util.SubstitutionException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.MojoNotFoundException;
import org.apache.maven.plugin.PluginConfigurationException;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginManagerException;
import org.apache.maven.plugin.PluginNotFoundException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Generates an application file by prepending a launch script to a Spring Boot executable jar.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME,
      threadSafe = true)
public class GenerateMojo extends BaseMojo {
    /**
     * Main class to be launched. Specified as a fully qualified Java Class name. Similar to the main class
     * specified when you start the JVM.
     */
    @Parameter(required = true)
    private String main;

    /**
     * Fixed options passed to Java VM. You can use this to make local shell variables used in the launch
     * script available Java. E.g. use "-Dapp=$APP" to make the fully qualified application name
     * available as a system property "app". Another example: use "-Dapporig=$0" to get the original name
     * this application was invoked with.
     */
    @Parameter(defaultValue = "")
    private String options = "";

    /**
     * Name of the Java Executable to be invoked by the script. Only a file name, without path.
     */
    @Parameter(defaultValue = "java")
    private String java = "";

    /**
     * Path to search the Java Executable. Defaults to the default search path. Change this variable to specify a different location
     * to search for java.
     */
    @Parameter(defaultValue = "$PATH")
    private String path = "";

    /**
     * Copied verbatim to the launch code right before the final Java call,
     * placing each extension on a new line.
     * You have access to the following script variables:
     *   MAIN (the main class as specified in your pom),
     *   NAME (application name as specified in your pom),
     *   OPTIONS (as specified in your pom) and
     *   APP (absolute normalized path to the application file).
     *
     * Note that these variables are not exported, to access them from your application, you have to
     * turn them into properties!
     */
    @Parameter
    private List<String> extensions = new ArrayList<>();

    /**
     * File with a launcher template. Overrides the built-in template.
     */
    @Parameter
    private String launcher = null;

    /**
     * Project jar to repackage when Maven has not assigned a project artifact file.
     */
    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}.jar")
    private String projectJar;

    /**
     * Version of the Spring Boot Maven Plugin. If omitted, the version is read from
     * the project plugin configuration or its resolved Spring Boot dependency.
     */
    @Parameter
    private String springBootVersion;

    /** When true, generated application files will be deployed to the Maven repository */
    @Parameter(defaultValue = "true")
    private boolean attach;

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component
    private BuildPluginManager buildPluginManager;

    /**
     * Maven ProjectHelper
     */
    @Component
    protected MavenProjectHelper projectHelper;

    public GenerateMojo() throws IOException {
        this(createWorld(), null, null, null, null, null, null);
    }

    private static World createWorld() {
        World world = World.createMinimal();
        world.withStandardFilesystems(false);
        return world;
    }

    public GenerateMojo(World world, String name, FileNode dir, String main, String classifier, String java, String path) {
        super(world);
        this.name = name;
        this.dir = dir;
        this.main = main;
        this.classifier = classifier;
        this.java = java;
        this.path = path;
        this.options = "";
    }

    public void execute() throws MojoExecutionException {
        try {
            doExecute();
        } catch (IOException e) {
            throw new MojoExecutionException("cannot generate application: " + e.getMessage(), e);
        }
    }

    public void doExecute() throws IOException, MojoExecutionException {
        validate(name);
        // do not wipe the directory because other plugins might already have copied stuff into it
        dir.mkdirsOpt();
        if (project.getArtifact().getFile() == null) {
            project.getArtifact().setFile(new File(projectJar));
        }
        repackage();
        File jar = project.getArtifact().getFile();
        verify(jar);
        script();
        jar(jar);
        getLog().info(">" + size(getFile().toPath().toFile()) + getFile());
        if (attach) {
            projectHelper.attachArtifact(project, type, classifier, getFile().toPath().toFile());
        }
    }

    private void repackage() throws MojoExecutionException {
        Plugin configuredPlugin;
        Plugin plugin;
        MojoExecution execution;
        Xpp3Dom configuration;
        Xpp3Dom mainClass;
        MojoDescriptor descriptor;
        String version;

        configuredPlugin = springBootPlugin();
        version = springBootVersion(configuredPlugin);
        if (version == null || version.isEmpty()) {
            throw new MojoExecutionException("cannot determine Spring Boot Maven Plugin version; set springBootVersion "
                    + "or declare a Spring Boot dependency/plugin version");
        }
        plugin = new Plugin();
        plugin.setGroupId("org.springframework.boot");
        plugin.setArtifactId("spring-boot-maven-plugin");
        plugin.setVersion(version);

        try {
            descriptor = buildPluginManager.getMojoDescriptor(plugin, "repackage",
                    project.getRemotePluginRepositories(), session.getRepositorySession());
            configuration = toXpp3Dom(descriptor.getMojoConfiguration());
            if (!"configuration".equals(configuration.getName())) {
                Xpp3Dom root = new Xpp3Dom("configuration");
                root.addChild(configuration);
                configuration = root;
            }
            if (configuredPlugin != null && configuredPlugin.getConfiguration() instanceof Xpp3Dom) {
                configuration = Xpp3Dom.mergeXpp3Dom(
                        new Xpp3Dom((Xpp3Dom) configuredPlugin.getConfiguration()), configuration);
            }
            mainClass = configuration.getChild("mainClass");
            if (mainClass == null) {
                mainClass = new Xpp3Dom("mainClass");
                configuration.addChild(mainClass);
            }
            mainClass.setValue(main);
            execution = new MojoExecution(descriptor, "application-plugin-repackage");
            execution.setConfiguration(configuration);
            buildPluginManager.executeMojo(session, execution);
        } catch (MojoFailureException | PluginConfigurationException | PluginManagerException | PluginNotFoundException
                 | PluginResolutionException | PluginDescriptorParsingException | MojoNotFoundException
                 | InvalidPluginDescriptorException e) {
            throw new MojoExecutionException("Spring Boot repackage failed: " + e.getMessage(), e);
        }
    }

    private static Xpp3Dom toXpp3Dom(PlexusConfiguration configuration) {
        Xpp3Dom result;

        result = new Xpp3Dom(configuration.getName());
        result.setValue(configuration.getValue(null));
        for (String attribute : configuration.getAttributeNames()) {
            result.setAttribute(attribute, configuration.getAttribute(attribute, null));
        }
        for (PlexusConfiguration child : configuration.getChildren()) {
            result.addChild(toXpp3Dom(child));
        }
        return result;
    }

    private Plugin springBootPlugin() {
        Plugin plugin;

        if (project.getBuild() == null) {
            return null;
        }
        for (Plugin candidate : project.getBuild().getPlugins()) {
            if ("org.springframework.boot".equals(candidate.getGroupId())
                    && "spring-boot-maven-plugin".equals(candidate.getArtifactId())) {
                return candidate;
            }
        }
        if (project.getBuild().getPluginManagement() != null) {
            for (Plugin candidate : project.getBuild().getPluginManagement().getPlugins()) {
                if ("org.springframework.boot".equals(candidate.getGroupId())
                        && "spring-boot-maven-plugin".equals(candidate.getArtifactId())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private String springBootVersion(Plugin configuredPlugin) {
        String version;

        version = springBootVersion;
        if (version == null || version.isEmpty()) {
            version = project.getProperties().getProperty("spring-boot.version");
        }
        if ((version == null || version.isEmpty()) && configuredPlugin != null) {
            version = configuredPlugin.getVersion();
        }
        if (version == null || version.isEmpty()) {
            for (Artifact artifact : project.getArtifacts()) {
                if ("org.springframework.boot".equals(artifact.getGroupId()) && "spring-boot".equals(artifact.getArtifactId())) {
                    return artifact.getVersion();
                }
            }
        }
        return version;
    }

    private void verify(File jar) throws MojoExecutionException {
        Manifest manifest;
        String startClass;

        if (jar == null || !jar.isFile()) {
            throw new MojoExecutionException("Spring Boot repackage did not produce an application jar");
        }
        try (JarFile jarFile = new JarFile(jar)) {
            manifest = jarFile.getManifest();
        } catch (IOException e) {
            throw new MojoExecutionException("cannot read repackaged jar " + jar + ": " + e.getMessage(), e);
        }
        if (manifest == null) {
            throw new MojoExecutionException("Spring Boot repackage did not add a manifest to " + jar);
        }
        startClass = manifest.getMainAttributes().getValue("Start-Class");
        if (!main.equals(startClass)) {
            throw new MojoExecutionException("Spring Boot jar Start-Class is " + startClass + ", expected " + main);
        }
    }

    private void script() throws IOException, MojoExecutionException {
        Node file;

        file = getFile();
        file.writeString(launcherTemplate());
        file.setPermissions(permissions);
    }

    public String launcherTemplate() throws IOException, MojoExecutionException {
        String str;
        Map<String, String> variables;
        Node src;

        variables = new HashMap<>();
        variables.put("path", path);
        variables.put("java", java);
        variables.put("name", name);
        variables.put("main", main);
        variables.put("options", options);
        variables.put("optionsVariable", getAppVarPrefix() + "_OPTS");
        variables.put("javaVariable", getAppVarPrefix() + "_JAVA");
        variables.put("extensions", Separator.RAW_LINE.join(extensions));
        src = launcher != null ? world.file(launcher) : world.resource("launcher");
        str = src.readString();
        try {
            return S.apply(str, variables);
        } catch (SubstitutionException e) {
            throw new MojoExecutionException("invalid launcher template: " + e.getMessage(), e);
        }
    }

    //--

    public static final Substitution S = new Substitution("${{", "}}", '\\');

    public static void validate(String name) throws MojoExecutionException {
        for (int i = 0, max = name.length(); i < max; i++) {
            if (Character.isWhitespace(name.charAt(i))) {
                throw new MojoExecutionException("invalid whitespace in application name: '" + name + "'");
            }
        }
    }

    //--

    public String getAppVarPrefix() {
        StringBuilder builder;
        char c;

        builder = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            c = name.charAt(i);
            if (Character.isLetter(c) || (i > 0 && Character.isDigit(c))) {
                builder.append(Character.toUpperCase(c));
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    public void jar(File jar) throws IOException, MojoExecutionException {
        if (jar.toPath().toAbsolutePath().normalize().equals(getFile().toPath().toAbsolutePath().normalize())) {
            throw new MojoExecutionException("application output must not overwrite the Spring Boot jar: " + jar);
        }
        try (OutputStream dest = getFile().newAppendStream()) {
            world.file(jar).copyFileTo(dest);
        }
    }

    private static String gav(Artifact artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
    }

    private static String size(File file) {
        return size(file.length());
    }

    private static String size(long length) {
        return Strings.padLeft("" + ((length + 512) / 1024), 5) + " kb ";
    }

}
