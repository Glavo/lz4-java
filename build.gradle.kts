import org.glavo.gradle.LWJGL

plugins {
    id("java")
}

group = "org.glavo"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(platform("org.junit:junit-bom:5.14.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testImplementation("com.carrotsearch.randomizedtesting:randomizedtesting-runner:2.7.7")
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

tasks.withType<JavaCompile> {
    options.release.set(17)
}

val testTempDir = layout.buildDirectory.dir("test-tmp")

tasks.test {
    useJUnitPlatform()
    systemProperty("net.jpountz.lz4.test.tempDir", testTempDir.get().asFile.absolutePath)
}