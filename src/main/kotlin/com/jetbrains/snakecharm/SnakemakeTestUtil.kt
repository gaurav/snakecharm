package com.jetbrains.snakecharm

import com.intellij.openapi.application.PathManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * @author Roman.Chernyatchik
 * @date 2019-02-03
 */
object SnakemakeTestUtil {
    private const val TEST_DATA_DIR = "testData"

    /**
     * Marker file that, together with [TEST_DATA_DIR], identifies the project home. A lone 'testData'
     * directory is not enough: the walk below runs all the way to the filesystem root, and an
     * unrelated ancestor that happens to own one (a CI workspace, ~/testData, an enclosing monorepo)
     * would be accepted silently — every test would then read fixtures from the wrong tree and fail
     * as a pile of "file not found" errors rather than as a locator problem.
     */
    private const val PROJECT_HOME_MARKER = "snakemake_api.yaml"

    fun getTestDataPath(): Path {
        val homePath = projectHomePath(SnakemakeTestUtil::class.java)
        checkNotNull(homePath) {
            "Could not locate the project home (a directory containing both '$TEST_DATA_DIR' and '$PROJECT_HOME_MARKER')."
        }
        return homePath.resolve(TEST_DATA_DIR)
    }

    private fun projectHomePath(aClass: Class<*>): Path? {
        val rootPath = PathManager.getResourceRoot(
                aClass,
                "/" + aClass.name.replace('.', '/') + ".class"
        ) ?: return null

        // The class is loaded either from the plugin jar inside the Gradle test sandbox
        // (e.g. <home>/.sandbox_pycharm/<projectName>/PY-2026.1.3/plugins-test/snakecharm/lib/snakecharm-*.jar)
        // or from a build output directory. The exact depth of the sandbox layout has changed across
        // platform / IntelliJ Platform Gradle Plugin versions (2026.1 added an extra <projectName> level),
        // so instead of counting a fixed number of parents we walk up to the nearest ancestor that
        // looks like the project home.
        var dir: Path? = File(rootPath).toPath().parent
        while (dir != null && !isProjectHome(dir)) {
            dir = dir.parent
        }
        return dir
    }

    private fun isProjectHome(dir: Path) =
            Files.isDirectory(dir.resolve(TEST_DATA_DIR)) && Files.isRegularFile(dir.resolve(PROJECT_HOME_MARKER))
}