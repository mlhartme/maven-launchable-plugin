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

import net.oneandone.sushi.archive.Archive;
import net.oneandone.sushi.archive.ArchiveException;
import net.oneandone.sushi.fs.Node;
import net.oneandone.sushi.fs.World;
import net.oneandone.sushi.fs.file.FileNode;
import net.oneandone.sushi.io.OS;
import net.oneandone.sushi.util.Separator;
import net.oneandone.sushi.util.Strings;
import net.oneandone.sushi.util.Substitution;
import net.oneandone.sushi.util.SubstitutionException;
import net.oneandone.sushi.xml.Builder;
import net.oneandone.sushi.xml.Dom;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.MojoNotFoundException;
import org.apache.maven.plugin.PluginConfigurationException;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginManagerException;
import org.apache.maven.plugin.PluginNotFoundException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import proguard.ClassPath;
import proguard.ClassPathEntry;
import proguard.Configuration;
import proguard.ConfigurationParser;
import proguard.ParseException;
import proguard.ProGuard;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
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
     * Dependency jar entries to be concatenated before adding them to the application file. Comma-separated list of patterns.
     */
    @Parameter(defaultValue = "")
    private String concat = "";

    /**
     * Dependency jar entries that will not be added to the application file. Comma-separated list of patterns.
     */
    @Parameter(defaultValue = "")
    private String remove = "";

    /**
     * Dependency jar file entries that may overlap: the last entry will be added to the application file, all
     * previous entries get lost. Comma-separated list of patterns.
     */
    @Parameter(defaultValue = "")
    private String overwrite = "";

    /**
     * Dependency jar file entries that may be duplicates if they are equal. Comma-separated list of patterns.
     * Only one of the duplicates will be added to the application file.
     */
    @Parameter(defaultValue = "")
    private String equal = "";

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
     * True to remove unused code from that application file. Be careful with this parameter - if your application relies on
     * reflection or introspection, you need proper shrinkOptions to declare them.
     */
    @Parameter(defaultValue = "false")
    private boolean shrink;

    /**
     * Proguard options as defined at http://proguard.sourceforge.net/manual/usage.html
     * The application plugin supplied proper in- and output options, library options and  a keep option for the main
     * method. It also disables obfuscation.
     *
     * Everything else you need can be defined here.
     */
    @Parameter(defaultValue = "")
    private String shrinkOptions = "";

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
     * Scopes to include in compound jar. There's usually no need to touch this option.
     * Defaults to "compile" and "runtime".
     */
    @Parameter
    private List<String> scopes = Arrays.asList(Artifact.SCOPE_COMPILE, Artifact.SCOPE_RUNTIME);

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
        validateUnsupportedOptions();
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

    private void validateUnsupportedOptions() throws MojoExecutionException {
        if (shrink || !shrinkOptions.isEmpty() || !concat.isEmpty() || !remove.isEmpty() || !overwrite.isEmpty()
                || !equal.isEmpty() || !scopes.equals(Arrays.asList(Artifact.SCOPE_COMPILE, Artifact.SCOPE_RUNTIME))) {
            throw new MojoExecutionException(
                    "Spring Boot repackage owns application packaging; shrink, concat, remove, overwrite, equal and scopes are not supported");
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

    private List<Artifact> getDependencies() {
        List<Artifact> artifacts;
        Artifact a;

        artifacts = new ArrayList<>();
        for (Iterator<?> i = project.getArtifacts().iterator(); i.hasNext();) {
            a = (Artifact) i.next();
            if (scopes.contains(a.getScope())) {
                artifacts.add(a);
            }
        }
        a = project.getArtifact();
        if (a.getFile() == null) {
            // happens if application:generate is called directly from the command line
            a.setFile(new File(projectJar));
        }
        artifacts.add(a);
        return artifacts;
    }

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

    private synchronized void proguard(Archive archive) throws IOException, MojoExecutionException {
        ProGuard pg;
        Configuration config;

        FileNode dir = world.getTemp().createTempDirectory();
        FileNode in;
        FileNode out;
        FileNode log;
        PrintStream oldOut;
        PrintStream oldErr;

        in = dir.join("in.jar");
        out = dir.join("out.jar");
        archive.save(in);

        config = new Configuration();
        try {
            addConfig("-keep public class " + main + " {\n  public static void main(java.lang.String[]); \n}\n", config);
        } catch (ParseException e) {
            throw new IllegalStateException(e);
        }
        config.shrink = true;
        config.obfuscate = false;
        config.optimize = true;
        config.libraryJars = cp(runtime());
        config.programJars = cp(in);
        config.programJars.add(new ClassPathEntry(out.toPath().toFile(), true));
        try {
            addConfig(shrinkOptions, config);
        } catch (ParseException e) {
            throw new MojoExecutionException("invalid shrink options: " + e.getMessage(), e);
        }
        log = world.file(projectJar).getParent().join(name + "-proguard.log");
        oldOut = System.out;
        oldErr = System.err;
        try (PrintStream stream = new PrintStream(log.newOutputStream())) {
            System.setOut(stream);
            System.setErr(stream);
            pg = new ProGuard(config);
            pg.execute();
        } catch (IOException e) {
            getLog().error("shrink failed, see " + log + " for details");
            throw e;
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        getLog().info("-" + size(in.size() - out.size()) + "shrunk by http://proguard.sourceforge.net/");
        try (OutputStream dest = getFile().newAppendStream()) {
            out.copyFileTo(dest);
        }
    }

    private void addConfig(String str, Configuration dest) throws ParseException, IOException {
        ConfigurationParser parser;

        parser = new ConfigurationParser(str, "note", world.getWorking().toPath().toFile(), System.getProperties());
        try {
            parser.parse(dest);
        } finally {
            parser.close();
        }
    }

    private FileNode runtime() throws IOException {
        FileNode result;

        result = world.file(System.getProperty("java.home"));
        result.checkDirectory();
        if (OS.beforeJava9()) {
            result = result.join("lib/rt.jar");
        } else {
            result = result.join("jmods/java.base.jmod");
        }
        result.checkFile();
        return result;
    }

    private static ClassPath cp(FileNode... entries) {
        ClassPath result;

        result = new ClassPath();
        for (FileNode entry : entries) {
            result.add(new ClassPathEntry(entry.toPath().toFile(), false));
        }
        return result;
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

    private void addDependencies(Archive archive) throws IOException, MojoExecutionException {
        Document plexus;
        Sources sources;
        File file;
        Archive add;
        FileNode jar;
        List<String> duplicatePaths;

        plexus = null;
        sources = new Sources();
        duplicatePaths = new ArrayList<>();
        for (Artifact artifact : getDependencies()) {
            getLog().info("+" + size(artifact.getFile()) + gav(artifact));
            file = artifact.getFile();
            if (file == null) {
                throw new IllegalStateException("unresolved dependency: " + gav(artifact) + ".jar");
            }
            jar = world.file(file);
            add = Archive.loadJar(jar);
            sources.addAll(add.data, artifact);
            removeFiles(add.data);
            concat(add.data, archive.data);
            copy(add.data, archive.data, duplicatePaths);
            archive.mergeManifest(add.manifest);
            plexus = plexusMerge(archive.data, plexus);
        }
        if (duplicatePaths.size() > 0) {
            sources.retain(duplicatePaths);
            throw new MojoExecutionException("duplicate files:\n" + sources.toString());
        }
        plexusSave(archive.data, plexus);
    }

    private static final String ROOT = "component-set";
    private static final String COMPONENTS = "components";

    private void copy(Node<?> srcdir, Node<?> destdir, List<String> duplicates) throws IOException, MojoExecutionException {
        List<Node> mayEqual;
        List<Node> mayOverwrite;
        Node destfile;
        String relative;

        mayEqual = find(srcdir, equal);
        mayOverwrite = find(srcdir, overwrite);
        for (Node<?> srcfile : srcdir.find("**/*")) {
            relative = srcfile.getRelative(srcdir);
            destfile = destdir.join(relative);
            if (srcfile.isDirectory()) {
                destfile.mkdirsOpt();
            } else {
                if (destfile.exists()) {
                    if (srcfile.diff(destfile)) {
                        if (mayOverwrite.contains(srcfile)) {
                            getLog().debug("overwrite different " + relative);
                            destfile.deleteFile();
                            srcfile.copyFile(destfile);
                        } else {
                            duplicates.add(relative);
                        }
                    } else {
                        if (mayOverwrite.contains(srcfile)) {
                            getLog().debug("overwrite equal " + relative);
                        } else if (mayEqual.contains(srcfile)) {
                            getLog().debug("equal " + relative);
                        } else {
                            duplicates.add(relative);
                        }
                    }
                } else {
                    srcfile.copyFile(destfile);
                }
            }
        }
    }

    private static List<String> split(String str) throws MojoExecutionException {
        List<String> result;

        result = Separator.COMMA.split(str);
        for (String item : result) {
            if (item.contains(" ")) {
                throw new MojoExecutionException(
                        "Invalid space character in configuration value " + str + "\n" + "Use commas to separate multiple entries.");
            }
        }
        return result;
    }

    private List<Node> find(Node<?> srcdir, String property) throws IOException, MojoExecutionException {
        List<Node> mayOverwrite;

        mayOverwrite = new ArrayList<>();
        for (String p : split(property)) {
            mayOverwrite.addAll(srcdir.find(p));
        }
        return mayOverwrite;
    }

    private void removeFiles(Node<?> srcdir) throws IOException, MojoExecutionException {
        for (String p : split(remove)) {
            removeFiles(srcdir, p);
        }
    }

    private void removeFiles(Node<?> srcdir, String removePath) throws IOException {
        for (Node srcfile : srcdir.find(removePath)) {
            if (srcfile.isDirectory()) {
                // skip - I'd delete all contained files
            } else {
                getLog().debug("removing " + srcfile);
                srcfile.deleteFile();
            }
        }
    }

    private void concat(Node srcdir, Node<?> destdir) throws IOException, MojoExecutionException {
        for (String p : split(concat)) {
            getLog().debug("concatenating " + p);
            concat(srcdir, destdir, p);
        }
    }

    private void concat(Node<?> srcdir, Node<?> destdir, String concatPath) throws IOException {
        for (Node srcfile : srcdir.find(concatPath)) {
            concatOne(srcdir, destdir, srcfile.getRelative(srcdir));
        }
    }

    private void concatOne(Node srcdir, Node destdir, String concatPath) throws IOException {
        Node src;
        Node dest;
        String lf;
        StringBuilder builder;
        int idx;

        src = srcdir.join(concatPath);
        dest = destdir.join(concatPath);
        if (!dest.exists()) {
            dest.getParent().mkdirsOpt();
            dest.writeString("");
        }
        lf = srcdir.getWorld().os.lineSeparator.getSeparator();
        builder = new StringBuilder();
        builder.append(src.readString());
        idx = builder.lastIndexOf(lf);
        if (idx + lf.length() != builder.length()) {
            builder.append(lf);
        }
        builder.append(dest.readString());
        dest.writeString(builder.toString());
        src.deleteFile();
        getLog().debug("merged " + concatPath + ":\n" + builder);
    }

    private Node plexusFile(Node root) {
        return root.join("META-INF/plexus/components.xml");
    }

    private void plexusSave(Node root, Document plexus) throws IOException {
        if (plexus != null) {
            getLog().debug("merged plexus components");
            plexusFile(root).writeXml(plexus);
        }
    }

    private Document plexusMerge(Node root, Document plexus) throws IOException {
        Element componentSet;
        Element components;
        Node file;

        file = plexusFile(root);
        if (!file.exists()) {
            return plexus;
        }
        if (plexus == null) {
            plexus = world.getXml().getBuilder().createDocument(ROOT);
            components = Builder.element(plexus.getDocumentElement(), COMPONENTS);
        } else {
            components = (Element) plexus.getDocumentElement().getFirstChild();
        }
        try {
            componentSet = file.readXml().getDocumentElement();
        } catch (SAXException e) {
            throw new IOException(file + ": " + e.getMessage());
        }
        if (!componentSet.getTagName().equals(ROOT)) {
            throw new IOException(file + ": expected " + ROOT);
        }
        for (Element e : Dom.getAllChildElements(componentSet)) {
            if (e.getTagName().equals(COMPONENTS)) {
                Builder.add(components, e.getChildNodes());
            } else {
                throw new IOException("unknown element: " + e.getTagName());
            }
        }
        file.deleteFile();
        return plexus;
    }

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");

    private void mainAttributes(Attributes attributes) throws ArchiveException {
        attributes.putValue("Specification-Title", project.getName());
        attributes.putValue("Specification-Version", project.getVersion());
        attributes.putValue("Specification-Vendor", getOrganization());
        attributes.putValue("Implementation-Title", project.getGroupId() + ":" + project.getArtifactId());
        attributes.putValue("Implementation-Version", DATE_FORMAT.format(new Date()));
        attributes.putValue("Implementation-Vendor", getUserEmail());
        attributes.putValue("Main-class", main);
    }

    private String getOrganization() {
        if (project.getOrganization() != null) {
            return project.getOrganization().getName();
        } else {
            return "unknown organization";
        }
    }

    public static String getUserEmail() {
        String hostname;

        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            // this happens if your hostname is not in /etc/hosts and not resolvable via dns.
            hostname = "unknownhost";
        }
        return System.getProperty("user.name") + "@" + hostname;
    }
}
