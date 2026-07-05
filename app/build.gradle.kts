plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Number of trivial classes to generate and force-load at startup (the CLASS_PREPARE storm).
// Override from the command line, e.g.:  ./gradlew :app:assembleDebug -PreproClassCount=40000
val reproClassCount: Int =
    (project.findProperty("reproClassCount") as String?)?.toIntOrNull() ?: 20_000

val generatedJavaDir = layout.buildDirectory.dir("generated/repro/java")

android {
    namespace = "com.example.artdeadlockrepro"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.artdeadlockrepro"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
        // Expose the generated class count to runtime so ReproApp loads exactly what was built.
        buildConfigField("int", "REPRO_CLASS_COUNT", "$reproClassCount")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Compile the generated classes as part of the app.
    sourceSets.getByName("main").java.srcDir(generatedJavaDir)
}

// Code-gen task: writes N trivial Java classes into gen/GenN.java.
// Incremental: only re-runs when reproClassCount changes.
val genReproClasses = tasks.register("genReproClasses") {
    val outDir = generatedJavaDir
    val count = reproClassCount
    outputs.dir(outDir)
    inputs.property("count", count)
    doLast {
        val pkg = outDir.get().asFile.resolve("gen")
        pkg.deleteRecursively()
        pkg.mkdirs()
        for (i in 0 until count) {
            pkg.resolve("Gen$i.java").writeText(
                "package gen;\npublic final class Gen$i { public int v() { return $i; } }\n"
            )
        }
        logger.lifecycle("genReproClasses: generated $count classes into $pkg")
    }
}

// Make sure classes exist before anything tries to compile them.
tasks.named("preBuild").configure { dependsOn(genReproClasses) }
