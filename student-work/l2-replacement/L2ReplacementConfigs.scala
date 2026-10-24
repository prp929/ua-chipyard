// Student project: L2 replacement-policy configuration scaffolding.
//
// The project runner applies the matching InclusiveCache patch before building
// this configuration.
package chipyard

import org.chipsalliance.cde.config._
import sifive.blocks.inclusivecache._

class WithInclusiveCacheReplacementPolicy(
  policy: InclusiveCacheReplacementPolicy,
  bipMruProbabilityNumerator: Int = 1,
  bipMruProbabilityDenominator: Int = 32,
  leaderSetsPerPolicy: Int = 16,
  pselCounterWidth: Int = 10
) extends Config((site, here, up) => {
  case InclusiveCacheReplacementKey => InclusiveCacheReplacementConfig(
    policy = policy,
    bipMruProbabilityNumerator = bipMruProbabilityNumerator,
    bipMruProbabilityDenominator = bipMruProbabilityDenominator,
    leaderSetsPerPolicy = leaderSetsPerPolicy,
    pselCounterWidth = pselCounterWidth
  )
})

/** Keep the core, HPM counters, and default InclusiveCache geometry identical
  * across the replacement-policy configurations below.
  */
class L2ReplacementBaseConfig extends Config(
  new chipyard.config.WithNPerfCounters(8) ++
  new WithBaselineCore(1) ++
  new chipyard.config.AbstractConfig
)

class L2RandomReplacementConfig extends Config(
  new WithInclusiveCacheReplacementPolicy(InclusiveCacheReplacementPolicy.Random) ++
  new L2ReplacementBaseConfig
)

class L2PLRUReplacementConfig extends Config(
  new WithInclusiveCacheReplacementPolicy(InclusiveCacheReplacementPolicy.PLRU) ++
  new L2ReplacementBaseConfig
)

class L2TrueLRUReplacementConfig extends Config(
  new WithInclusiveCacheReplacementPolicy(InclusiveCacheReplacementPolicy.TrueLRU) ++
  new L2ReplacementBaseConfig
)

class L2BIPReplacementConfig extends Config(
  new WithInclusiveCacheReplacementPolicy(InclusiveCacheReplacementPolicy.BIP) ++
  new L2ReplacementBaseConfig
)

class L2DIPReplacementConfig extends Config(
  new WithInclusiveCacheReplacementPolicy(InclusiveCacheReplacementPolicy.DIP) ++
  new L2ReplacementBaseConfig
)

/** Fully implemented worked example; compare your own policies against it. */
class L2RoundRobinDemoReplacementConfig extends Config(
  new WithInclusiveCacheReplacementPolicy(InclusiveCacheReplacementPolicy.RoundRobin) ++
  new L2ReplacementBaseConfig
)
