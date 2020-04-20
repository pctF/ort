/*
 * Copyright (C) 2019 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.helper.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file

import org.ossreviewtoolkit.helper.common.fetchScannedSources
import org.ossreviewtoolkit.helper.common.getLicenseFindingsById
import org.ossreviewtoolkit.helper.common.getPackageOrProject
import org.ossreviewtoolkit.helper.common.getViolatedRulesByLicense
import org.ossreviewtoolkit.helper.common.replaceConfig
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.OrtResult
import org.ossreviewtoolkit.model.Severity
import org.ossreviewtoolkit.model.TextLocation
import org.ossreviewtoolkit.model.readValue
import org.ossreviewtoolkit.utils.expandTilde

import java.io.File

internal class ListLicensesCommand : CliktCommand(
    name = "list-licenses",
    help = "Lists the license findings for a given package as distinct text locations."
) {
    private val ortResultFile by option(
        "--ort-result-file",
        help = "The ORT result file to read as input."
    ).convert { it.expandTilde() }
        .file(mustExist = true, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = true)
        .required()

    private val packageId by option(
        "--package-id",
        help = "The target package for which the licenses shall be listed."
    ).convert { Identifier(it) }
        .required()

    private val sourceCodeDir by option(
        "--source-code-dir",
        help = "A directory containing the sources for the target package. These sources should match the provenance " +
                "of the respective scan result in the ORT result. If not specified those sources are downloaded if " +
                "needed."
    ).convert { it.expandTilde() }
        .file(mustExist = true, canBeFile = false, canBeDir = true, mustBeWritable = false, mustBeReadable = true)

    private val onlyOffending by option(
        "--only-offending",
        help = "Only list licenses causing a rule violation of error severity in the given ORT result."
    ).flag()

    private val omitExcluded by option(
        "--omit-excluded",
        help = "Only list license findings for non-excluded file locations."
    ).flag()

    private val ignoreExcludedRuleIds by option(
        "--ignore-excluded-rule-ids",
        help = "A comma separated list of rule names for which --omit-excluded should not have any effect."
    ).convert { it.split(",").toList() }
        .default(emptyList())

    private val noLicenseTexts by option(
        "--no-license-texts",
        help = "Do not output the actual file content of file locations of license findings."
    ).flag()

    private val applyLicenseFindingCurations by option(
        "--apply-license-finding-curations",
        help = "Apply the license finding curations contained in the ORT result."
    ).flag()

    private val repositoryConfigurationFile by option(
        "--repository-configuration-file",
        help = "Override the repository configuration contained in the ORT result."
    ).convert { it.expandTilde() }
        .file(mustExist = true, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = true)

    override fun run() {
        val ortResult = ortResultFile.readValue<OrtResult>().replaceConfig(repositoryConfigurationFile)

        if (ortResult.getPackageOrProject(packageId) == null) {
            throw UsageError("Could not find a package for the given id `$packageId`.")
        }

        val sourcesDir = if (sourceCodeDir == null) {
            println("Downloading sources for package $packageId...")
            ortResult.fetchScannedSources(packageId)
        } else {
            sourceCodeDir!!
        }
        val violatedRulesByLicense = ortResult.getViolatedRulesByLicense(packageId, Severity.ERROR)

        fun isPathExcluded(path: String) = ortResult.repository.config.excludes.findPathExcludes(path).isNotEmpty()

        ortResult
            .getLicenseFindingsById(packageId, applyLicenseFindingCurations)
            .filter { (license, _) -> !onlyOffending || violatedRulesByLicense.contains(license) }
            .mapValues { (license, locations) ->
                locations.filter {
                    !omitExcluded || !isPathExcluded(it.path) ||
                            ignoreExcludedRuleIds.intersect(violatedRulesByLicense[license].orEmpty()).isNotEmpty()
                }
            }
            .mapValues { it.value.groupByText(sourcesDir) }
            .writeValueAsString(
                isPathExcluded = { path -> isPathExcluded(path) },
                includeLicenseTexts = !noLicenseTexts
            )
            .let { println(it) }
    }
}

private data class TextLocationGroup(
    val locations: Set<TextLocation>,
    val text: String? = null
)

private fun Collection<TextLocationGroup>.assignReferenceNameAndSort(): List<Pair<TextLocationGroup, String>> {
    var i = 0
    return sortedWith(compareBy({ it.text == null }, { -it.locations.size }))
        .map {
            if (it.text != null) {
                Pair(it, "${i++}")
            } else {
                Pair(it, "-")
            }
        }
}

private fun Map<String, List<TextLocationGroup>>.writeValueAsString(
    isPathExcluded: (String) -> Boolean,
    includeLicenseTexts: Boolean = true
): String {
    return buildString {
        fun appendlnIndent(value: String, indent: Int) {
            require(indent > 0)
            appendln(value.replaceIndent(" ".repeat(indent)))
        }

        this@writeValueAsString.forEach { (license, textLocationGroups) ->
            appendlnIndent("$license:", 2)

            val sortedGroups = textLocationGroups.assignReferenceNameAndSort()
            sortedGroups.forEach { (group, name) ->
                group.locations.forEach {
                    val excludedIndicator = if (isPathExcluded(it.path)) "(-)" else "(+)"
                    appendlnIndent(
                        "[$name] $excludedIndicator ${it.path}:${it.startLine}-${it.endLine}",
                        4
                    )
                }
            }

            if (includeLicenseTexts) {
                sortedGroups.forEach { (group, name) ->
                    if (group.text != null) {
                        appendlnIndent("\n\n[$name]", 4)
                        appendlnIndent("\n\n${group.text}\n", 6)
                    }
                }
            }

            appendln()
        }
    }
}

private fun Collection<TextLocation>.groupByText(baseDir: File): List<TextLocationGroup> {
    val resolvedLocations = mutableMapOf<String, MutableSet<TextLocation>>()

    forEach { textLocation ->
        textLocation.resolve(baseDir)?.let {
            resolvedLocations.getOrPut(it, { mutableSetOf() }).add(textLocation)
        }
    }

    val unresolvedLocations = (this - resolvedLocations.values.flatten()).distinct()

    return resolvedLocations.map { (text, locations) -> TextLocationGroup(locations = locations, text = text) } +
            unresolvedLocations.map { TextLocationGroup(locations = setOf(it)) }
}

private fun TextLocation.resolve(baseDir: File): String? {
    val file = baseDir.resolve(path)
    if (!file.isFile) return null

    val lines = file.readText().lines()
    if (lines.size <= endLine) return null

    return lines.subList(startLine - 1, endLine).joinToString(separator = "\n")
}
