package com.cfst.app.speedtest

import com.cfst.app.utils.IpRangeParser
import java.math.BigInteger

/** No address-space expansion: storage is O(input ranges + cap), including /0. */
class CandidateSampler(private val sources:List<IpRangeParser.IpRange>, private val cap:Int, private val seed:Long) {
    companion object {
        const val ALGORITHM_VERSION = 2
        @Volatile internal var preparationObserver:(()->Unit)?=null
        // SplitMix64 finalizer: all seed bits influence each domain before bounded reduction.
        private fun mix64(value:Long):Long {
            var z=value
            z=(z xor (z ushr 30)) * -4658895280553007687L
            z=(z xor (z ushr 27)) * -7723592293110705685L
            return z xor (z ushr 31)
        }
    }
    enum class Status { CAP_REACHED, POOL_EXHAUSTED, LIMITED_SOURCE, NO_CANDIDATES }
    data class Report(val status:Status, val eligibleCount:String, val priorityNotCovered:String, val limitedSources:Int, val attempts:Int)
    data class Sample(val candidates:Set<String>, val report:Report)
    private data class Interval(val lo:BigInteger,val hi:BigInteger,val v6:Boolean) {
        val size get()=hi-lo+BigInteger.ONE
    }
    private fun format(n:BigInteger,v6:Boolean)=if(!v6) IpRangeParser.longToIp(n.toLong()) else (7 downTo 0).joinToString(":") { n.shiftRight(it*16).and(BigInteger.valueOf(65535)).toString(16) }
    fun sample():Sample=sampleChecked {}
    internal fun sampleChecked(check:()->Unit):Sample {
        fun tick() { check() }
        val order=Comparator<Interval> { a,b -> tick(); compareValuesBy(a,b,{it.v6},{it.lo},{it.hi}) }
        fun merge(input:List<Interval>):List<Interval> {
            val out=mutableListOf<Interval>()
            for(r in input.sortedWith(order)) {
                tick()
                val last=out.lastOrNull()
                if(last!=null && last.v6==r.v6 && r.lo<=last.hi+BigInteger.ONE) out[out.lastIndex]=last.copy(hi=maxOf(last.hi,r.hi)) else out.add(r)
            }
            return out
        }
        fun coprimeStep(n:Long,salt:Long):Long {
            if(n<=1) return 1
            var step=Math.floorMod(salt,n).coerceAtLeast(1)
            while(BigInteger.valueOf(step).gcd(BigInteger.valueOf(n))!=BigInteger.ONE) { tick();step++;if(step>=n) step=1 }
            return step
        }
        require(cap in 1..10000 && sources.size<=10000)
        val raw=sources.map { r ->
            preparationObserver?.invoke();tick()
            require(r.startIp.signum()>=0 && r.startIp<=r.endIp && r.endIp.bitLength()<=if(r.isIpv6) 128 else 32)
            Interval(r.startIp,r.endIp,r.isIpv6)
        }
        var limited=0
        val prioritySources=raw.filter { tick(); if(it.v6 && it.size>BigInteger.valueOf(256)) limited++;it.size<=BigInteger.valueOf(if(it.v6) 256 else 16) }
            .distinct().sortedWith(Comparator { a,b -> tick();compareValuesBy(a,b,{it.size!=BigInteger.ONE},{it.v6},{it.lo},{it.hi}) })
        // Sweep endpoints once. The lowest source index owns each span: literals first,
        // then canonical small-source order. Fragments preserve round-robin provenance.
        data class Edge(val at:BigInteger,val v6:Boolean,val index:Int,val add:Boolean)
        val edges=prioritySources.flatMapIndexed { i,s -> tick();listOf(Edge(s.lo,s.v6,i,true),Edge(s.hi+BigInteger.ONE,s.v6,i,false)) }
            .sortedWith(Comparator { a,b -> tick();compareValuesBy(a,b,{it.v6},{it.at}) })
        val fragments=Array(prioritySources.size) { mutableListOf<Interval>() }
        val active=java.util.TreeSet<Int>()
        var e=0
        while(e<edges.size) {
            tick();val edge=edges[e]
            while(e<edges.size && edges[e].v6==edge.v6 && edges[e].at==edge.at) {
                tick();val event=edges[e++];if(event.add) active.add(event.index) else active.remove(event.index)
            }
            if(active.isNotEmpty() && e<edges.size && edges[e].v6==edge.v6) fragments[active.first()].add(Interval(edge.at,edges[e].at-BigInteger.ONE,edge.v6))
        }
        val covered=merge(fragments.flatMap { tick();it })
        val priorityCount=covered.fold(0L) { sum,it -> tick();sum+it.size.toLong() }
        val chosen=linkedSetOf<String>();var attempts=0
        fun emitGroup(group:List<List<Interval>>,room:Int) {
            val counts=group.map { fs -> tick();fs.sumOf { tick();it.size.toInt() } }
            val max=counts.maxOrNull() ?: 0
            val rounds=LongArray(max+1)
            val endings=Array(max+1) { mutableListOf<Int>() }
            counts.forEachIndexed { i,c -> tick();endings[c].add(i) }
            var live=counts.count { it>0 }
            for(round in 0 until max) { tick();rounds[round+1]=rounds[round]+live;live-=endings[round+1].size }
            val total=rounds[max]
            if(total==0L || room<=0) return
            // Fenwick active-source index supports rotated rank/select without rescanning all sources per emit.
            val tree=IntArray(counts.size+1)
            fun add(index:Int,delta:Int) { var i=index+1;while(i<tree.size) { tick();tree[i]+=delta;i+=i and -i } }
            fun select(slot:Int):Int { var i=0;var left=slot;var bit=Integer.highestOneBit(counts.size);while(bit>0) { tick();val next=i+bit;if(next<tree.size && tree[next]<=left) { i=next;left-=tree[next] };bit=bit shr 1 };return i }
            var currentRound=-1
            fun atRank(rank:Long):Pair<Int,Int> {
                var low=0;var high=max
                while(low+1<high) { tick();val mid=(low+high)/2;if(rounds[mid]<=rank) low=mid else high=mid }
                val round=low
                if(currentRound<0 || round<currentRound) {
                    tree.fill(0);counts.forEachIndexed { i,c -> tick();if(c>round) add(i,1) }
                } else for(done in currentRound+1..round) endings[done].forEach { tick();add(it,-1) }
                currentRound=round
                return select((rank-rounds[round]).toInt()) to round
            }
            val offset=if(total>room) Math.floorMod(seed,total) else 0L
            repeat(minOf(room.toLong(),total).toInt()) { index ->
                tick();val (source,round)=atRank((offset+index)%total)
                var addressIndex=round
                val fragment=group[source].first { tick();if(addressIndex<it.size.toInt()) true else { addressIndex-=it.size.toInt();false } }
                chosen.add(format(fragment.lo+BigInteger.valueOf(addressIndex.toLong()),fragment.v6));attempts++
            }
        }
        val literals=fragments.indices.filter { tick();prioritySources[it].size==BigInteger.ONE }.map { fragments[it] }
        val small=fragments.indices.filter { tick();prioritySources[it].size!=BigInteger.ONE }.map { fragments[it] }
        emitGroup(literals,cap);emitGroup(small,cap-chosen.size)
        val uncovered=(priorityCount-chosen.size).coerceAtLeast(0)
        val union=merge(raw.filter { tick();!it.v6 })
        val v4covered=covered.filter { tick();!it.v6 }
        val broad=mutableListOf<Interval>();var c=0
        for(source in union) {
            tick();var cursor=source.lo
            while(c<v4covered.size && v4covered[c].hi<cursor) { tick();c++ }
            while(c<v4covered.size && v4covered[c].lo<=source.hi) {
                tick();val cut=v4covered[c++]
                if(cut.lo>cursor) broad.add(Interval(cursor,cut.lo-BigInteger.ONE,false))
                cursor=maxOf(cursor,cut.hi+BigInteger.ONE)
            }
            if(cursor<=source.hi) broad.add(Interval(cursor,source.hi,false))
        }
        val eligible=BigInteger.valueOf(priorityCount)+broad.fold(BigInteger.ZERO) { sum,it -> tick();sum+it.size }
        val blocks=merge(broad.map { tick();Interval(it.lo.shiftRight(8),it.hi.shiftRight(8),false) })
        val blockPrefix=LongArray(blocks.size+1)
        blocks.forEachIndexed { i,b -> tick();blockPrefix[i+1]=blockPrefix[i]+b.size.toLong() }
        val blockCount=blockPrefix.last()
        val start=if(blockCount>0) Math.floorMod(mix64(seed xor 0x243f6a8885a308d3L),blockCount) else 0L
        val step=coprimeStep(blockCount,mix64(seed xor 0x13198a2e03707344L))
        var cursor=0L;var emptyBatches=0
        while(chosen.size<cap && attempts<30*cap && emptyBatches<3 && blockCount>0) {
            tick();val before=chosen.size
            repeat(minOf(cap,30*cap-attempts)) {
                tick();if(chosen.size>=cap) return@repeat
                val lane=cursor/blockCount
                val blockIndex=(start+(cursor%blockCount)*step)%blockCount
                cursor++;attempts++
                var low=0;var high=blocks.size
                while(low+1<high) { tick();val mid=(low+high)/2;if(blockPrefix[mid]<=blockIndex) low=mid else high=mid }
                val block=blocks[low].lo+BigInteger.valueOf(blockIndex-blockPrefix[low])
                val lo=block.shiftLeft(8);val hi=lo+BigInteger.valueOf(255)
                low=0;high=broad.size
                while(low<high) { tick();val mid=(low+high)/2;if(broad[mid].hi<lo) low=mid+1 else high=mid }
                val members=mutableListOf<Interval>()
                while(low<broad.size && broad[low].lo<=hi) { tick();val b=broad[low++];members.add(Interval(maxOf(lo,b.lo),minOf(hi,b.hi),false)) }
                val count=members.sumOf { tick();it.size.toLong() }
                if(lane>=count) return@repeat
                // Derive a local bounded permutation only for this visited /24. Domain
                // separation avoids linking its host octet to either block rank or step.
                val local=java.util.Random(mix64(seed xor mix64(block.toLong() xor 0x6a09e667f3bcc909L)))
                val offset=local.nextInt(count.toInt()).toLong()
                val memberStep=coprimeStep(count,local.nextLong())
                var member=(offset+lane*memberStep)%count
                val fragment=members.first { tick();if(member<it.size.toLong()) true else { member-=it.size.toLong();false } }
                chosen.add(format(fragment.lo+BigInteger.valueOf(member),false))
            }
            emptyBatches=if(chosen.size==before) emptyBatches+1 else 0
        }
        tick()
        val status=when { chosen.isEmpty()->Status.NO_CANDIDATES;limited>0->Status.LIMITED_SOURCE;chosen.size>=cap->Status.CAP_REACHED;else->Status.POOL_EXHAUSTED }
        return Sample(chosen,Report(status,eligible.toString(),uncovered.toString(),limited,attempts))
    }
}
