package io.github.definitelystable.spongetube.testsupport.playback.f1

import io.github.definitelystable.spongetube.core.engine.ExtentPlaybackResource
import io.github.definitelystable.spongetube.core.engine.FetchKey
import io.github.definitelystable.spongetube.core.engine.InlinePlaybackResource
import io.github.definitelystable.spongetube.core.engine.PlaybackFetchUnit
import io.github.definitelystable.spongetube.core.engine.PlaybackPlan
import io.github.definitelystable.spongetube.core.engine.PlaybackResource
import io.github.definitelystable.spongetube.core.engine.SpongeBridgeApi
import io.github.definitelystable.spongetube.core.storage.ExtentId
import io.github.definitelystable.spongetube.core.storage.ExtentSpec
import io.github.definitelystable.spongetube.core.storage.Sha256Digest
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1Catalog
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FetchUnit
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureAssets
import io.github.definitelystable.spongetube.testsupport.fixture.f1.F1FixtureCatalog
import io.github.definitelystable.spongetube.testsupport.fixture.f1.sha256Hex

const val F1_PLAYBACK_AUTHORITY = "fixture-f1"
const val F1_MANIFEST_KEY = "manifest.mpd"

/**
 * Canonical F1 PlaybackPlan builder shared by M1-E and M1-F.
 *
 * Keeping this mapping in one test-support module prevents verification
 * harnesses from silently assigning different FetchKey/Extent identities to
 * the same fixture bytes.
 */
@OptIn(SpongeBridgeApi::class)
class F1PlaybackPlanFactory(
    private val fixture: F1FixtureAssets,
    val catalog: F1Catalog = fixture.catalog(),
) {
    fun plan(
        splitExtentIds: Set<String> = emptySet(),
        parts: Int = 3,
    ): PlaybackPlan {
        val mpdFact = fixture.resourceFacts.getValue(
            F1FixtureCatalog.MANIFEST_PATH,
        )
        val resources = mutableListOf<PlaybackResource>(
            InlinePlaybackResource(
                key = F1_MANIFEST_KEY,
                bytes = fixture.verifiedBytes(F1FixtureCatalog.MANIFEST_PATH),
                expectedLength = mpdFact.length,
                expectedSha256 = Sha256Digest(mpdFact.sha256),
            ),
        )
        for (unit in catalog.units) {
            val units = if (unit.extentId in splitExtentIds) {
                split(unit, parts)
            } else {
                listOf(
                    PlaybackFetchUnit(
                        fetchKey = FetchKey(unit.fetchKeyValue),
                        extentSpec = unit.toExtentSpec(),
                        resourceStart = 0,
                        transportKey = unit.resourcePath,
                    ),
                )
            }
            resources += ExtentPlaybackResource(
                unit.resourceName,
                unit.length,
                units,
            )
        }
        return PlaybackPlan(F1FixtureCatalog.ASSET, resources)
    }

    fun seed(targetUs: Long): List<F1FetchUnit> =
        catalog.units.filter { unit ->
            val start = unit.mediaStartUs
            start == null || start < targetUs
        }

    private fun split(
        unit: F1FetchUnit,
        parts: Int,
    ): List<PlaybackFetchUnit> {
        require(parts > 0)
        val bytes = fixture.verifiedBytes(unit.resourcePath)
        val boundaries = (0..parts).map { index ->
            unit.length * index / parts
        }
        return boundaries.zipWithNext().map { (start, end) ->
            PlaybackFetchUnit(
                fetchKey = FetchKey(
                    "${unit.fetchKeyValue}#bytes=$start-${end - 1}",
                ),
                extentSpec = ExtentSpec(
                    mediaAssetId = F1FixtureCatalog.ASSET,
                    extentId = ExtentId(
                        "${unit.extentId}@$start-$end",
                    ),
                    trackId = unit.trackId,
                    representationId = unit.representationId,
                    mediaStartUs = null,
                    mediaEndUs = null,
                    byteStart = start,
                    byteEndExclusive = end,
                    dependencyExtentIds =
                        unit.dependencyExtentIds.map(::ExtentId),
                    expectedLength = end - start,
                    expectedSha256 = Sha256Digest(
                        sha256Hex(
                            bytes,
                            start.toInt(),
                            (end - start).toInt(),
                        ),
                    ),
                ),
                resourceStart = start,
                transportKey = unit.resourcePath,
            )
        }
    }
}
