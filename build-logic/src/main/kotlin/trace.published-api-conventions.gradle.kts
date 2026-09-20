import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
  id("trace.java-conventions")
  id("com.vanniktech.maven.publish")
}

// Publishing to Maven Central goes through the Central Portal; OSSRH was shut down in 2025.
// Signing is only configured when a key is actually present, so `publishToMavenLocal` works on a
// developer machine with no GPG setup.
val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent

extensions.configure<MavenPublishBaseExtension> {
  configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = SourcesJar.Sources()))
  publishToMavenCentral()
  if (hasSigningKey) {
    signAllPublications()
  }
  coordinates(project.group.toString(), project.name, project.version.toString())
  pom {
    name.set(project.name)
    description.set("Public API for Trace, a block logging and rollback plugin for Paper servers.")
    url.set("https://github.com/gravaxis/trace")
    inceptionYear.set("2026")
    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("repo")
      }
    }
    developers {
      developer {
        id.set("gravaxis")
        name.set("Gravaxis")
        url.set("https://gravaxis.in")
      }
    }
    scm {
      url.set("https://github.com/gravaxis/trace")
      connection.set("scm:git:https://github.com/gravaxis/trace.git")
      developerConnection.set("scm:git:ssh://git@github.com/gravaxis/trace.git")
    }
  }
}
