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
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")

    testImplementation("com.carrotsearch.randomizedtesting:randomizedtesting-runner:2.7.7")
    testImplementation("com.code-intelligence:jazzer-junit:0.26.0")
}

tasks.withType<JavaCompile> {
    options.release.set(11)
}

val testTempDir = layout.buildDirectory.dir("test-tmp")

tasks.test {
    useJUnitPlatform()
    systemProperty("net.jpountz.lz4.test.tempDir", testTempDir.get().asFile.absolutePath)
}