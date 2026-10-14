plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.i2p.crypto:eddsa:0.3.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("dev.pocketrun.keygen.MainKt")
}

tasks.test { useJUnit() }
