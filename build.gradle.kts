plugins {
    java
}

group = "net.alloymc"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files("libs/alloy-api.jar"))
    compileOnly(files("libs/alloy-loader.jar"))
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks.withType<JavaCompile> {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.jar {
    archiveBaseName.set("AlloyShop")
    archiveVersion.set("1.0.0")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })

    manifest {
        attributes("Implementation-Title" to "AlloyShop")
        attributes("Implementation-Version" to "1.0.0")
    }
}
