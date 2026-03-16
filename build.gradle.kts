import com.sun.management.OperatingSystemMXBean
import org.glavo.gradle.LWJGL
import java.lang.management.ManagementFactory
import kotlin.math.max

plugins {
    id("java-library")
    id("jacoco")
    id("maven-publish")
    id("signing")
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
    id("org.glavo.load-maven-publish-properties") version "0.1.0"
}

group = "org.glavo"
version = "1.10.4.1" + "-SNAPSHOT"
description = "LZ4 Java implementation"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.code-intelligence:jazzer-junit:0.26.0")

    val lwjglVersion = "3.4.1"
    testImplementation("org.lwjgl:lwjgl:$lwjglVersion")
    testImplementation("org.lwjgl:lwjgl-lz4:$lwjglVersion")
    testImplementation("org.lwjgl:lwjgl-xxhash:$lwjglVersion")

    if (LWJGL.PLAFROM != null) {
        testImplementation("org.lwjgl:lwjgl:${lwjglVersion}:natives-${LWJGL.PLAFROM}")
        testImplementation("org.lwjgl:lwjgl-lz4:${lwjglVersion}:natives-${LWJGL.PLAFROM}")
        testImplementation("org.lwjgl:lwjgl-xxhash:${lwjglVersion}:natives-${LWJGL.PLAFROM}")
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).also {
        it.encoding("UTF-8")
        it.addStringOption("link", "https://docs.oracle.com/en/java/javase/17/docs/api/")
        it.addBooleanOption("html5", true)
        it.addStringOption("Xdoclint:none", "-quiet")
    }
}

val testFlipByteOrder by tasks.registering(Test::class) {
    group = "verification"

    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    systemProperty("net.jpountz.lz4.test.flipByteOrder", true)
}

tasks.check {
    dependsOn(testFlipByteOrder)
}

val testTempDir = layout.buildDirectory.dir("test-tmp")

tasks.withType<Test> {
    useJUnitPlatform()

    systemProperty("net.jpountz.lz4.test.tempDir", testTempDir.get().asFile.absolutePath)

    if (project.findProperty("net.jpountz.lz4.test.fuzz")?.toString().equals("true", true)) {
        environment("JAZZER_FUZZ", "1")
    }

    // Use more parallelism on large machines
    if ((ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean).totalMemorySize >= 14L * 1024L * 1024L * 1024L) {
        maxParallelForks = max(1, Runtime.getRuntime().availableProcessors() / 4)
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test, testFlipByteOrder)
    executionData(testFlipByteOrder.get())

    reports {
        xml.required.set(true)
        csv.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

publishing.publications.create<MavenPublication>("maven") {
    groupId = project.group.toString()
    version = project.version.toString()
    artifactId = project.name

    from(components["java"])

    pom {
        name.set(project.name)
        description.set(project.description)
        url.set("https://github.com/Glavo/lz4-java")

        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }

        developers {
            developer {
                id.set("Glavo")
                name.set("Glavo")
                email.set("zjx001202@gmail.com")
            }
        }

        scm {
            url.set("https://github.com/Glavo/lz4-java")
        }
    }
}

if (rootProject.ext.has("signing.key")) {
    signing {
        useInMemoryPgpKeys(
            rootProject.ext["signing.keyId"].toString(),
            rootProject.ext["signing.key"].toString(),
            rootProject.ext["signing.password"].toString(),
        )
        sign(publishing.publications["maven"])
    }
}

// ./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

            username.set(rootProject.ext["sonatypeUsername"].toString())
            password.set(rootProject.ext["sonatypePassword"].toString())
        }
    }
}
