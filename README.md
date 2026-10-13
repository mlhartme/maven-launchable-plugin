# Maven Launchable Plugin

generates a *launchable* - a single file with your Java application.
If Java is installed, the launchable can be executed as is, it behaves like a native file.
It's self-contained, you can move the launchable around, rename it and send it to others.

Technically, the launchable is a shell script (aka *launcher*) with a jar file appended.
Unix sees the launchable as a shell script - thus, it can execute it. 
Java sees it as a jar file - thus, it can use it in its classpath.

Implementation: The jar part of the launchable is generated like Spring Boot's "repackaged" 
files; in fact, it uses Spring Boot's mechanism to package a jar with all dependencies into 
a single file. The launcher is simply prepended to this file.


## Prerequisites

* Java 21 or newer
* the launchable works for Linux or macOS. Windows is not supported.
* if you want to build the plugin: Maven 3

## Setup

Add

    <plugin>
      <groupId>net.oneandone.maven.plugins</groupId>
      <artifactId>launchable</artifactId>
      <version>2.0.0</version>
      <configuration>
        <name>yourapp</name>
        <main>foo.yourapp.Main</main>
      </configuration>
      <executions>
        <execution>
          <goals>
            <goal>generate</goal>
          </goals>
        </execution>
      </executions>
    </plugin>

to your pom.xml and run `mvn clean package` to generate the launchable `target/yourapp`.

## Deployment

Run `mvn deploy` to deploy a launchable to a Maven repository. The plugin attaches your launchable 
to the build and thus Maven will upload it into your repository. Please note that the launchable 
file name in the repository is your project's artifact name with "-launchable.sh" appended, it's not 
the name you specified with the name parameter. If users download launchables from the repository, they have 
to adjust the file name and make the file executable (e.g with `chmod`). If you want to download with the 
`maven-dependency-plugin`, specify the artifacts of your project together with classifier `launchable` and type `sh`. 
Example: `mvn dependency:copy "-Dartifact=my.group:my-artifact:1.0.0:sh:launchable"`


[Goal documentation](http://mlhartme.github.io/maven-launchable-plugin/launchable/plugin-info.html)

## Alternatives

Other plugins with similar functionality:

  * Maven's [Shade Plugin](http://maven.apache.org/plugins/maven-shade-plugin/plugin-info.html) can package all classes into a single jar file,
    possibly renaming duplicate classes
  * Maven's [Assembly Plugin](http://maven.apache.org/plugins/maven-assembly-plugin/) can create arbitrary archives, including jars.
