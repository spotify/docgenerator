Docgenerator
============

Generates a maven report named rest.html that contains the `javax.ws.rs` annotated HTTP endpoint
methods and Jackson `@JsonProperty` annotated objects, and a few other things.


#To Use
## Source Code Annotations

If you do nothing, you'll still get some manner of usable
documentation, however I presume you probably want more than that.

If you add `@DocEnum` annotations to your enums, you can add javadoc
to the individual values in the enum, as well as to the enum itself,
rather than the fairly bland thing it does otherwise.

On your Resource methods, you can add `@ArgumentDoc` annotations on
the arguments to the method which can contain doc on the individual
parameters to the method.

Then for an example exchange, there are the `@ExampleRequest`,
`@ExampleResponse` and `@ExampleArgs` annotations that are mostly-self
exlpanatory

  * You can put `@ExampleRequest("text of request body here")` and it
    will make an example request item in the document.  If the method
    is annotated with `@Consumes(APPLICATION_JSON)`, it will reformat
    the JSON, so you can pack it in the source code if you like.  *
    Likewise there is an `@ExampleResponse("text of response here")`
    which acts the same way as `@ExampleRequest`.

  * Then there is `@ExampleArgs("varname=value|other=other_Value...")`.
    If you have path arguments, when composing the url in the
    documentation, it will substitute occurrences of `{varname}` with
    the value from the annotation.

If you use the Example annotations, when you set up the plugin in maven,
you will/may want to set a few things:

  * `<endpointPrefix>` Allows you to prefix your endpoint urls which
    can be handy depending on how the thing is actually deployed
  * `<exampleHostPort>` When composing the url in the examples, this is
    the hostname:port it will use.  The `:port` part can be omitted.
  * `<examplesAreSsl>` It will generate examples that use SSL.  Defaults
    to true.


## Maven Configuration

There are two parts to the documentation generator:

  1. The annotation processor that scans the code for annotations and
     drops files based upon what it finds
  2. The maven report plugin that takes those things, and possibly jar
     file locations, and generates the report.

First, to add the annotation processor to your code, add the following
bit to the `pom.xml` of your project.

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
        <version>0.0.2-SNAPSHOT</version>
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
  
Note, if your documentation requires, as the example above, other
submodules of your reactor project, make sure to list them as
dependencies in the `<dependencies>` section, so they will be built
before the documentation gets built.
 
If you have enumerated types that you want documented, you'll need to include
the dependency which includes them.  See below.

If the return content type of your method is `application/json` and
you provided an example json response, the docgenerator will attempt
to deserialize it and let you know if it fails.  In order to do this,
the dependency which contains the thing to be deserialized needs to be
added.  See below.  If you need to turn validation off, you can set the
`skipValidation` property to `true`.

To include dependencies for use by the docgenerator, you have two
choices.  The first is to use the `<jarFiles>` notation as above.
This shouldn't be your first choice, but can work if others fail.
Your first choice is to add the dependency as you would any other
maven dependency as a dependency of the `maven-site-plugin`.  For
example, if you needed to include a class from the `javax.ws.rs-api`
version 2.0.1, you'd do this:

```xml
  <build>
    <plugins>
      <plugin>
	<groupId>org.apache.maven.plugins</groupId>
	<artifactId>maven-site-plugin</artifactId>
 	<dependencies>
	  <dependency>
	    <groupId>javax.ws.rs</groupId>
	    <artifactId>javax.ws.rs-api</artifactId>
	    <version>2.0.1</version>
	  </dependency>
	</dependencies>
      </plugin>
    </plugins>
  </build>
```

N.B. For some reason, using the normal maven dependency thing doesn't quite
work and as a result, may get errors from Jackson about "cannot instantiate
JSON object (need to add/enable type information?)".  In these cases,
assuming the class in question is properly configured and has appropriate
`@JsonProperty` annotations, try using the `jarFile` configuration property
instead to see if it goes away.  If you can explain to me why this happens,
pull requests definitely welcomed.

## Building the Docs
To build the docs, it should be a simple matter of running:

```shell
mvn package site
```
 
After it's done, the docs should be in `target/site/rest.html`.
  
## Examples

I'm glad you asked.  If you look in `testproject` youll find a pojo and a
resource class which you can look at to see how these things turn into 
documentation.  To build it all:

    mvn clean package site

and look in the target `testproject/target/site` directory, and load the file
named `rest.html`.


#TODO
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