import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.31"
    application
}

group = "me.roemer"
version = "1.0-SNAPSHOT"
val arguments: MutableList<String> = ArrayList()
arguments.add("-ea")

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.5.31")
    implementation("org.jgrapht:jgrapht-core:1.5.0")
    implementation("guru.nidi:graphviz-java:0.18.1")
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.12.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.12.2")
    implementation("com.fasterxml.jackson.module:jackson-module-afterburner:2.12.2")
    implementation(files("libs/lpsolve55j.jar"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.6.0")
}

application {
    mainClass.set("roemer.rebalancing.MainKt")
    applicationDefaultJvmArgs = arguments
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions { 
        jvmTarget = "11"
    }
}

tasks {
    "run"(JavaExec::class) {
        environment("LD_LIBRARY_PATH", "/usr/local/lib")
    }
}