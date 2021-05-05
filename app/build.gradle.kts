plugins {
    id("org.jetbrains.kotlin.jvm") version "1.4.31"
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.2")
    implementation("org.jgrapht:jgrapht-core:1.5.0")
    implementation("guru.nidi:graphviz-java:0.18.1")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.6.0")
}

application {
    mainClass.set("roemer.rebalancing.MainKt")
    applicationDefaultJvmArgs = arguments
}