# Maven Application Plugin

generates an application file. An application file is a single executable file containing your Java application. It consists of a shell script
with the executable Spring Boot jar appended. You can invoke it directly or pass it as an argument to `java -jar`. You can rename it without
breaking the application.

## Prerequisites

* Java 17 or newer
* the application file works for Linux or Mac OS. Windows is not supported.
* if you want to build the plugin: Maven 3

## Setup

Add

    <plugin>
      <groupId>net.oneandone.maven.plugins</groupId>
      <artifactId>application</artifactId>
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

to the build/plugins element in your pom.xml and run `mvn clean package` to generate the application file `target/yourapp`.

Note: to deploy the application into your Maven repository, run mvn deploy. The plugin attaches your application file and thus Maven will 
upload it into your repository. Please note that the deployed application file name is your projects artifact name with "-application.sh" 
appended, it's not the name you specified with the name parameter. If users download application files from the repository, they have to 
adjust the file name and make the file executable (e.g with `chmod`). If you want to download with the `maven-dependency-plugin`, specify
the artifacts of your probject together with classifier `application` and `sh`. Example: `mvn dependency:copy "-Dartifact=my.group:my-artifact:1.0.0:sh:application"

Note 2: you can use `jar tf yourapp` to list the files in the resulting application file.

[Goal documentation](http://mlhartme.github.io/maven-application-plugin/application/plugin-info.html)

## Spring Boot packaging

The plugin uses Spring Boot's Loader Tools API directly to repackage the project jar with its compile- and runtime-scope dependencies, then
wraps the executable jar in the application launcher. Set `<main>` to the application's main class. The repackage implementation is provided
by Spring Boot Loader Tools 4.1.1; no Spring Boot Maven Plugin configuration is required.


## Alternatives

Other plugins with similar functionality:

  * Maven's [Shade Plugin](http://maven.apache.org/plugins/maven-shade-plugin/plugin-info.html) can package all classes into a single jar file,
    possibly renaming duplicate classes
  * Maven's [Assembly Plugin](http://maven.apache.org/plugins/maven-assembly-plugin/) can create arbitrary archives, including jars.
