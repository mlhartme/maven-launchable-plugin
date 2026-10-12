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
package net.oneandone.maven.plugins.launchable;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.springframework.boot.loader.tools.Library;
import org.springframework.boot.loader.tools.LibraryCoordinates;
import org.springframework.boot.loader.tools.LibraryScope;
import org.springframework.boot.loader.tools.Libraries;
import org.springframework.boot.loader.tools.Repackager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Generates a launchable by prepending a launch script to a Spring Boot repackaged jar.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME,
      threadSafe = true)
public class GenerateMojo extends AbstractMojo {
    /**
     * Directory where to place the launcher and executable jar.
     */
    @Parameter(defaultValue = "${project.build.directory}")
    private File buildDirectory;

    /**
     * Name for the generated launchable file.
     */
    @Parameter(defaultValue = "${project.artifactId}")
    private String name;

    /**
     * Classifier to deploy launchable with.
     */
    @Parameter(defaultValue = "launchable")
    private String classifier = "";

    /**
     * Type to deploy the launchable with.
     */
    @Parameter(defaultValue = "sh")
    private String type = "";

    /**
     * Permissions for launchable file.
     */
    @Parameter(defaultValue = "rwxr-xr-x")
    private String permissions = "";

    /**
     * Main class to be launched. Specified as a fully qualified Java Class name. Similar to the main class
     * specified when you start the JVM.
     */
    @Parameter(required = true)
    private String main;

    /**
     * Fixed options passed to Java VM. You can use this to make local shell variables used in the launch
     * script available Java. E.g. use "-Dapp=$APP" to make the fully qualified launchable name
     * available as a system property "app". Another example: use "-Dapporig=$0" to get the original name
     * this launchable was invoked with.
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
     *   NAME (launchable name as specified in your pom),
     *   OPTIONS (as specified in your pom) and
     *   APP (absolute normalized path to the launchable file).
     *
     * Note that these variables are not exported, to access them from your launchable, you have to
     * turn them into properties!
     */
    @Parameter
    private List<String> extensions = new ArrayList<>();

    /**
     * Path to a launcher template. Overrides the built-in template.
     */
    @Parameter
    private File launcher = null;

    /**
     * Project jar to repackage when Maven has not assigned a project artifact file.
     */
    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}.jar")
    private String projectJar;

    /** When true, generated launchable will be deployed to the Maven repository */
    @Parameter(defaultValue = "true")
    private boolean attach;

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    /**
     * Maven ProjectHelper
     */
    @Component
    protected MavenProjectHelper projectHelper;

    public GenerateMojo() {
    }

    public void execute() throws MojoExecutionException {
        try {
            doExecute();
        } catch (IOException e) {
            throw new MojoExecutionException("cannot generate launchable: " + e.getMessage(), e);
        }
    }

    public void doExecute() throws IOException, MojoExecutionException {
        validate(name);
        Files.createDirectories(buildDirectory.toPath());
        if (project.getArtifact().getFile() == null) {
            project.getArtifact().setFile(new File(projectJar));
        }

        var sourceJar = project.getArtifact().getFile().toPath();
        var repackagedJar = Files.createTempFile("repackaged", ".jar");
        var launchable = buildDirectory.toPath().resolve(project.getBuild().getFinalName() + (classifier.isEmpty() ? "" : "-" + classifier) + "." + type);
        var symlink = buildDirectory.toPath().resolve(name);

        repackage(sourceJar, repackagedJar);
        verify(repackagedJar);
        write(repackagedJar, launchable);
        Files.createSymbolicLink(symlink, launchable.getFileName());

        getLog().info("generated " + launchable + size(Files.size(launchable)));
        if (attach) {
            projectHelper.attachArtifact(project, type, classifier, launchable.toFile());
        }
    }

    private void repackage(Path sourceJar, Path destJar) throws IOException {
        Repackager repackager = new Repackager(sourceJar.toFile());
        repackager.setMainClass(main);
        repackager.repackage(destJar.toFile(), libraries());
    }

    private Libraries libraries() {
        List<Artifact> artifacts = new ArrayList<>();
        for (Artifact artifact : project.getArtifacts()) {
            if (artifact.getArtifactHandler().isAddedToClasspath()
                    && (Artifact.SCOPE_COMPILE.equals(artifact.getScope()) || Artifact.SCOPE_RUNTIME.equals(artifact.getScope()))) {
                artifacts.add(artifact);
            }
        }
        return callback -> {
            for (Artifact artifact : artifacts) {
                var scope = Artifact.SCOPE_COMPILE.equals(artifact.getScope()) ? LibraryScope.COMPILE : LibraryScope.RUNTIME;
                var file = artifact.getFile();
                if (file == null || !file.isFile()) {
                    throw new IOException("unresolved dependency: " + artifact.getId());
                }
                callback.library(new Library(file.getName(), file, scope,
                        LibraryCoordinates.of(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()),
                        false, false, true));
            }
        };
    }

    private void verify(Path jar) throws MojoExecutionException, IOException {
        Manifest manifest;
        String startClass;

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            manifest = jarFile.getManifest();
        }
        if (manifest == null) {
            throw new MojoExecutionException("Spring Boot repackage did not add a manifest to " + jar);
        }
        startClass = manifest.getMainAttributes().getValue("Start-Class");
        if (!main.equals(startClass)) {
            throw new MojoExecutionException("Spring Boot jar Start-Class is " + startClass + ", expected " + main);
        }
    }

    private void write(Path repackaged, Path launchable) throws IOException, MojoExecutionException {
        Files.writeString(launchable, launcherTemplate(), StandardCharsets.UTF_8);
        try (OutputStream os = Files.newOutputStream(launchable, StandardOpenOption.APPEND)) {
            Files.copy(repackaged, os);
        }
        Files.setPosixFilePermissions(launchable, PosixFilePermissions.fromString(permissions));
    }

    public String launcherTemplate() throws IOException, MojoExecutionException {
        String str;
        Map<String, String> variables;

        variables = new HashMap<>();
        variables.put("path", path);
        variables.put("java", java);
        variables.put("name", name);
        variables.put("main", main);
        variables.put("options", options);
        variables.put("optionsVariable", getAppVarPrefix() + "_OPTS");
        variables.put("javaVariable", getAppVarPrefix() + "_JAVA");
        variables.put("extensions", String.join("\n", nonEmpty(extensions)));
        if (launcher != null) {
            str = Files.readString(launcher.toPath(), StandardCharsets.UTF_8);
        } else {
            try (InputStream input = GenerateMojo.class.getResourceAsStream("/launcher")) {
                if (input == null) {
                    throw new IOException("missing launcher resource");
                }
                str = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return substitute(str, variables);
    }

    private static String substitute(String content, Map<String, String> variables) throws MojoExecutionException {
        StringBuilder result = new StringBuilder();
        int start;
        int end;
        int last = 0;

        while ((start = content.indexOf("${{", last)) != -1) {
            end = content.indexOf("}}", start + 3);
            if (start > 0 && content.charAt(start - 1) == '\\') {
                result.append(content, last, start - 1).append("${{");
                last = start + 3;
            } else {
                if (end == -1) {
                    throw new MojoExecutionException("invalid launcher template: missing end marker");
                }
                String variable = content.substring(start + 3, end);
                String value = variables.get(variable);
                if (value == null) {
                    throw new MojoExecutionException("invalid launcher template: undefined variable: " + variable);
                }
                result.append(content, last, start).append(value);
                last = end + 2;
            }
        }
        result.append(content.substring(last));
        return result.toString();
    }

    private static List<String> nonEmpty(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    public static void validate(String name) throws MojoExecutionException {
        for (int i = 0, max = name.length(); i < max; i++) {
            if (Character.isWhitespace(name.charAt(i))) {
                throw new MojoExecutionException("invalid whitespace in launchable name: '" + name + "'");
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

    private static String size(long length) {
        String value = Long.toString((length + 512) / 1024);
        return " ".repeat(Math.max(0, 5 - value.length())) + value + " kb ";
    }
}
