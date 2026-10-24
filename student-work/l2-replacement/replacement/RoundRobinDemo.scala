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
import chisel3.util._

/** WORKED EXAMPLE, fully implemented: per-set round-robin replacement.
  *
  * Use it as a template for PLRU, TrueLRU, BIP and DIP. Select it with the
  * L2RoundRobinDemoReplacementConfig config.
  *
  * Policy: each set remembers a pointer to the way to evict next. When a line
  * is filled into way w, the pointer moves to w + 1 (wrapping). Hits do not
  * change anything, so this is FIFO-like and ignores recency.
  *
  * How the hooks are called (see Directory.scala and MSHR.scala):
  *  - victimWayOH: combinational. Returns a ONE-HOT way vector for the lookup
  *    whose result is valid this cycle; `set` is that lookup's set. Only used
  *    if the lookup misses.
  *  - acceptedHit: a lookup hit `way` in `set` (`enable` says it is real).
  *  - allocation: a new line was written into `way` of `set` after a miss.
  *  - invalidation: a valid line was removed from `way` of `set`.
  *  Every hook must produce hardware every cycle; gate state updates with `enable`.
  *
  * State is a Mem indexed by set (one small entry per set). Memories are not
  * reset, so the read value is clamped to a valid way in case a way count is
  * not a power of two and the initial contents are arbitrary.
  */
class RoundRobinInclusiveCacheReplacementState(params: InclusiveCacheParameters)
  extends InclusiveCacheReplacementState(params) {

  private val ways = params.cache.ways
  private val nextWay = Mem(params.cache.sets, UInt(params.wayBits.W))

  private def validWay(w: UInt): UInt = Mux(w >= ways.U, 0.U, w)

  def victimWayOH(readEnable: Bool, resultValid: Bool, set: UInt): UInt = {
    val victimWayOH = UIntToOH(validWay(nextWay(set)), ways)
    assert (!resultValid || PopCount(victimWayOH) === 1.U)
    victimWayOH
  }

  // Round robin ignores hits.
  def acceptedHit(set: UInt, way: UInt, enable: Bool): Unit = {}

  def allocation(set: UInt, way: UInt, enable: Bool): Unit = {
    when (enable) {
      nextWay(set) := Mux(way >= (ways - 1).U, 0.U, way + 1.U)
    }
  }

  // Round robin does not track per-way state, so removal needs no action.
  def invalidation(set: UInt, way: UInt, enable: Bool): Unit = {}
}
