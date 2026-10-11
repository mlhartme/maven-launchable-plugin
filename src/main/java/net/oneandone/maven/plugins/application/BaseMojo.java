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
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

public abstract class BaseMojo extends AbstractMojo {
    protected final World world;

    /**
     * Directory where to place the Launch Script and the executable Jar file.
     * Usually, there's no need to change the default value, which is target.
     */
    @Parameter(defaultValue = "${project.build.directory}")
    protected FileNode dir;

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

    public BaseMojo(World world) {
        this.world = world;
    }

    public void setDir(String dir) {
        this.dir = world.file(dir);
    }

    public Node getDir() {
        return dir;
    }

    public FileNode getFile() {
        return dir.join(name);
    }
}
