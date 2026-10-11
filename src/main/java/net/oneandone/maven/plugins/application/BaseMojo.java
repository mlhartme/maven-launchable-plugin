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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Path;
import java.nio.file.Paths;

public abstract class BaseMojo extends AbstractMojo {
    /**
     * Directory where to place the Launch Script and the executable Jar file.
     * Usually, there's no need to change the default value, which is target.
     */
    @Parameter(defaultValue = "${project.build.directory}")
    protected String dir;

    /**
     * Name for the generated application file.
     */
    @Parameter(defaultValue = "${project.artifactId}")
    protected String name;

    /**
     * Classifier to deploy application files with.
     * Specify a different value if you want to deploy multiple applications.
     */
    @Parameter(defaultValue = "application")
    protected String classifier = "";

    /**
     * Type to deploy application files with.
     */
    @Parameter(defaultValue = "sh")
    protected String type = "";

    /**
     * Permissions for application file.
     */
    @Parameter(defaultValue = "rwxr-xr-x")
    protected String permissions = "";

    protected BaseMojo() {
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public Path getDir() {
        return Paths.get(dir);
    }

    public Path getFile() {
        return getDir().resolve(name);
    }
}
