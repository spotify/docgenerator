Docgenerator
============

Generates a maven report named rest.html that contains the `javax.ws.rs` annotated HTTP endpoint
methods and Jackson `@JsonProperty` annotated objects, and a few other things.


#To Use
## Maven Configuration
There are two parts to the documentation generator:
  1. The annotation processor that scans the code for annotations and drops files based upon what it
   finds
  2. The maven report plugin that takes those things, and possibly jar file locations, and generates
   the report. 

First, to add the annotation processor to your code, add the following bit to the `pom.xml` of you
project.

```xml
    <dependency>
      <groupId>com.spotify.docgenerator</groupId>
      <artifactId>scanner</artifactId>
      <version>WHATEVER_THE_LATEST_VERSION_IS</version>
    </dependency>

```

Then for the actual documentation, if you're not using a reactor project, you'll include something
like this:
```xml
  <reporting>
    <plugins>
      <plugin>
        <groupId>com.spotify.docgenerator</groupId>
        <artifactId>docgenerator-maven-plugin</artifactId>
        <version>0.0.1</version>
        <configuration>
          <jsonClassesFiles>
            <jsonClassesFile>${project.build.directory}/classes/JSONClasses</jsonClassesFile>
          </jsonClassesFiles>
          <restEndpointsFiles>
            <restEndpointsFile>${project.build.directory}/classes/RESTEndpoints</restEndpointsFile>
          </restEndpointsFiles>
        </configuration>
      </plugin>
    </plugins>
  </reporting>
```

If you're in a reactor project (such as [Helios](http://github.com/spotify/helios), from which
this project originated), it may look something like this:
```xml
  <reporting>
    <plugins>
      <plugin>
        <groupId>com.spotify.docgenerator</groupId>
        <artifactId>docgenerator-maven-plugin</artifactId>
        <version>0.0.1</version>
        <configuration>
          <jsonClassesFiles>
            <jsonClassesFile>${project.build.directory}/../../helios-client/target/classes/JSONClasses</jsonClassesFile>
          </jsonClassesFiles>
          <restEndpointsFiles>
            <restEndpointsFile>${project.build.directory}/../../helios-services/target/classes/RESTEndpoints</restEndpointsFile>
          </restEndpointsFiles>
          <jarFiles>
            <jarFile>${project.build.directory}/../../helios-client/target/helios-client-${project.version}.jar</jarFile>
          </jarFiles>
        </configuration>
      </plugin>
    </plugins>
  </reporting>
```
  
Note, if your documentation requires, as the example above, other submodules of your reactor 
project, make sure to list them as dependencies in the `<dependencies>` section, so they will
be built before the documentation gets built.
 
The `<jarFiles>` bits are primarily for the case of `enum` types.  Basically, it's for types
referenced by one of the Jackson-serialized classes, that themselves aren't Jackson-serialized.
  
## Building the Docs
To build the docs, it should be a simple matter of running:

```shell
mvn package site
```
 
After it's done, the docs should be in `target/site/rest.html`.
  
#TODO
* The Javadoc processing is pretty pathetic
* Someone who has visual design skills could provide very useful improvements.
* maybe: Alternatively to doing the maven report plugin thing, a separate tool could be written that
  processed the output files and produced pretty docs.
  
#Under The Hood
The annotation processor will cause two files to be dropped in the `target/classes` directory when
the package is built.  They are named `JSONClasses` which will be serialized form of 
`Map<String, TransferClass>` which describes the Jackson annotated classes it found, and the
other file is named `RESTEndpoints` which is the serialized form of `List<ResourceMethod>`
describing the `javax.ws.rs` methods it found.  The serialized classes in question are in the
`common` package.