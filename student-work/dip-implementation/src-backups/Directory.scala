/*
 * Copyright 2019 SiFive, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You should have received a copy of LICENSE.Apache2 along with
 * this software. If not, you may obtain a copy at
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
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import MetaData._
import chisel3.experimental.dataview._
import freechips.rocketchip.util.{DescribedSRAM, TrueLRU}

class DirectoryEntry(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val dirty   = Bool() // true => TRUNK or TIP
  val state   = UInt(params.stateBits.W)
  val clients = UInt(params.clientBits.W)
  val tag     = UInt(params.tagBits.W)
}

class DirectoryWrite(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set  = UInt(params.setBits.W)
  val way  = UInt(params.wayBits.W)
  val data = new DirectoryEntry(params)
}

class DirectoryRead(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val set = UInt(params.setBits.W)
  val tag = UInt(params.tagBits.W)
}

class DirectoryResult(params: InclusiveCacheParameters) extends DirectoryEntry(params)
{
  val hit = Bool()
  val way = UInt(params.wayBits.W)
}

class Directory(params: InclusiveCacheParameters) extends Module
{
  val io = IO(new Bundle {
    val write  = Flipped(Decoupled(new DirectoryWrite(params)))
    val read   = Flipped(Valid(new DirectoryRead(params))) // sees same-cycle write
    val result = Valid(new DirectoryResult(params))
    val ready  = Bool() // reset complete; can enable access
  })

  val codeBits = new DirectoryEntry(params).getWidth

  val cc_dir =  DescribedSRAM(
    name = "cc_dir",
    desc = "Directory RAM",
    size = params.cache.sets,
    data = Vec(params.cache.ways, UInt(codeBits.W))
  )

  val write = Queue(io.write, 1) // must inspect contents => max size 1
  // a flow Q creates a WaR hazard... this MIGHT not cause a problem
  // a pipe Q causes combinational loop through the scheduler

  // Wiping the Directory with 0s on reset has ultimate priority
  val wipeCount = RegInit(0.U((params.setBits + 1).W))
  val wipeOff = RegNext(false.B, true.B) // don't wipe tags during reset
  val wipeDone = wipeCount(params.setBits)
  val wipeSet = wipeCount(params.setBits - 1,0)

  io.ready := wipeDone
  when (!wipeDone && !wipeOff) { wipeCount := wipeCount + 1.U }
  assert (wipeDone || !io.read.valid)

  // Be explicit for dumb 1-port inference
  val ren = io.read.valid
  val wen = (!wipeDone && !wipeOff) || write.valid
  assert (!io.read.valid || wipeDone)

  require (codeBits <= 256)

  write.ready := !io.read.valid
  when (!ren && wen) {
    cc_dir.write(
      Mux(wipeDone, write.bits.set, wipeSet),
      VecInit.fill(params.cache.ways) { Mux(wipeDone, write.bits.data.asUInt, 0.U) },
      UIntToOH(write.bits.way, params.cache.ways).asBools.map(_ || !wipeDone))
  }

  val ren1 = RegInit(false.B)
  val ren2 = if (params.micro.dirReg) RegInit(false.B) else ren1
  ren2 := ren1
  ren1 := ren

  val bypass_valid = params.dirReg(write.valid)
  val bypass = params.dirReg(write.bits, ren1 && write.valid)
  val regout = params.dirReg(cc_dir.read(io.read.bits.set, ren), ren1)
  val tag = params.dirReg(RegEnable(io.read.bits.tag, ren), ren1)
  val set = params.dirReg(RegEnable(io.read.bits.set, ren), ren1)

  // --------------------------------------------------------------------------
  // Stage 1: TrueLRU Replacement & Touch Logic
  // --------------------------------------------------------------------------
  val lru_logic = new TrueLRU(params.cache.ways)
  val lru_state = RegInit(VecInit(Seq.fill(params.cache.sets)(0.U(lru_logic.nBits.W))))

  // Read current set's recency state and derive victim way
  val current_lru_state = lru_state(set)
  val lru_victim_way = lru_logic.get_replace_way(current_lru_state)
  val victimWay = lru_victim_way
  val victimWayOH = UIntToOH(victimWay, params.cache.ways)
  assert (!ren2 || PopCount(victimWayOH) === 1.U)

  val setQuash = bypass_valid && bypass.set === set
  val tagMatch = bypass.data.tag === tag
  val wayMatch = bypass.way === victimWay

  val ways = regout.map(d => d.asTypeOf(new DirectoryEntry(params)))
  val hits = Cat(ways.zipWithIndex.map { case (w, i) =>
    w.tag === tag && w.state =/= INVALID && (!setQuash || i.U =/= bypass.way)
  }.reverse)
  val hit = hits.orR

  val result_hit = hit || (setQuash && tagMatch && bypass.data.state =/= INVALID)
  val hit_way = Mux(hit, OHToUInt(hits), bypass.way)

  io.result.valid := ren2
  io.result.bits.viewAsSupertype(chiselTypeOf(bypass.data)) := Mux(hit, Mux1H(hits, ways), Mux(setQuash && (tagMatch || wayMatch), bypass.data, Mux1H(victimWayOH, ways)))
  io.result.bits.hit := result_hit
  io.result.bits.way := Mux(result_hit, hit_way, victimWay)

  params.ccover(ren2 && setQuash && tagMatch, "DIRECTORY_HIT_BYPASS", "Bypassing write to a directory hit")
  params.ccover(ren2 && setQuash && !tagMatch && wayMatch, "DIRECTORY_EVICT_BYPASS", "Bypassing a write to a directory eviction")

  // --------------------------------------------------------------------------
  // Stage 2: BIP & DIP Set Dueling Logic
  // --------------------------------------------------------------------------
  val policy = params.micro.policy.toLowerCase
  val is_pure_lru = (policy == "lru").B
  val is_pure_bip = (policy == "bip").B
  val is_dip_mode = (!is_pure_lru && !is_pure_bip)

  // BIP throttle: 5-bit counter
  val bip_counter = RegInit(0.U(5.W))

  // Set Dueling:
  // Dedicated LRU sets (32 sets): set[9:5] === set[4:0]
  // Dedicated BIP sets (32 sets): set[9:5] === ~set[4:0]
  // Follower sets (960 sets): !is_lru_set && !is_bip_set
  val is_lru_set = if (params.setBits >= 10) (set(9, 5) === set(4, 0)) else true.B
  val is_bip_set = if (params.setBits >= 10) (set(9, 5) === (~set(4, 0))(4, 0)) else false.B

  // 10-bit saturating counter PSEL, initialized to 512
  val psel = RegInit(512.U(10.W))

  when (is_dip_mode && ren2 && !result_hit) {
    when (is_lru_set && psel < 1023.U) {
      psel := psel + 1.U
    } .elsewhen (is_bip_set && psel > 0.U) {
      psel := psel - 1.U
    }
  }

  // Follower decision & policy selection:
  // Use BIP when:
  // - Pure BIP mode
  // - DIP mode on a dedicated BIP set
  // - DIP mode on a follower set when psel(9) === 1.U
  val use_bip = Mux(is_pure_lru, false.B,
                Mux(is_pure_bip, true.B,
                Mux(is_lru_set, false.B,
                Mux(is_bip_set, true.B,
                psel(9) === 1.U))))

  // Connect recency state updates
  when (ren2) {
    when (result_hit) {
      // On a hit: update recency state promoting hit_way to MRU
      lru_state(set) := lru_logic.get_next_state(current_lru_state, hit_way)
    } .otherwise {
      // On an eviction / insertion:
      when (use_bip) {
        bip_counter := bip_counter + 1.U
        when (bip_counter === 31.U) {
          // Insert at MRU with probability 1/32
          lru_state(set) := lru_logic.get_next_state(current_lru_state, victimWay)
        } .otherwise {
          // Insert at LRU with probability 31/32: state unchanged (retains LRU position)
          lru_state(set) := current_lru_state
        }
      } .otherwise {
        // TrueLRU insertion: insert at MRU
        lru_state(set) := lru_logic.get_next_state(current_lru_state, victimWay)
      }
    }
  }

  def json: String = s"""{"clients":${params.clientBits},"mem":"${cc_dir.pathName}","clean":"${wipeDone.pathName}"}"""
}
