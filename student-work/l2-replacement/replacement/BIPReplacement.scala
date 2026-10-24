/*
 * Copyright 2019 SiFive, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sifive.blocks.inclusivecache

import chisel3._

/** STUB: BIP replacement is not implemented yet.
  * Constructing it fails elaboration; the factory never substitutes another policy.
  *
  * TODO: recency order as in true LRU, but allocation inserts at LRU except
  * with probability bipMruProbabilityNumerator / bipMruProbabilityDenominator
  * (seeded LFSR); acceptedHit still promotes to MRU.
  */
class BIPInclusiveCacheReplacementState(params: InclusiveCacheParameters)
  extends InclusiveCacheReplacementState(params) {
  InclusiveCacheReplacementState.unimplemented(InclusiveCacheReplacementPolicy.BIP)

  def victimWayOH(readEnable: Bool, resultValid: Bool, set: UInt): UInt = ???
  def acceptedHit(set: UInt, way: UInt, enable: Bool): Unit = ???
  def allocation(set: UInt, way: UInt, enable: Bool): Unit = ???
  def invalidation(set: UInt, way: UInt, enable: Bool): Unit = ???
}
