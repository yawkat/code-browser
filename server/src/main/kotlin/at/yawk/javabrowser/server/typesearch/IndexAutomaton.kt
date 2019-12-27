package at.yawk.javabrowser.server.typesearch

import at.yawk.numaec.BufferBasedCollection
import at.yawk.numaec.LargeByteBufferAllocator
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.collect.Iterators
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap
import org.eclipse.collections.impl.factory.primitive.ObjectIntMaps
import java.io.Serializable
import java.util.Collections

/**
 * @author yawkat
 */
internal class IndexAutomaton<E : Any> private constructor(private val automata: List<Automaton<E>>) : Serializable, BufferBasedCollection {
    constructor(
            entries: List<E>,
            components: (E) -> List<String>,
            jumps: Int,
            /**
             * Chunk size for individual automata. Larger chunk size leads to faster querying at cost of memory consumption.
             *
             * Rough measurements: https://s.yawk.at/l5G8Up7Z
             */
            chunkSize: Int = 512,
            storage: LargeByteBufferAllocator? = null
    ) : this(Iterables.partition(entries, chunkSize).map { chunk ->
        val nfa = Automaton(ImmutableList.copyOf(chunk)) // copy for serialization
        val root = nfa.stateBuilder()
        val cache = ObjectIntMaps.mutable.empty<StateRequest>()
        for ((i, entry) in chunk.withIndex()) {
            root.addTransition(Automaton.EPSILON, fromInput(
                    nfa = nfa,
                    cache = cache,
                    components = components(entry),
                    finalIndex = i,
                    request = StateRequest(0, 0, jumps, true)
            ))
            cache.clear()
        }
        root.finish()
        nfa.startState = root.state

        nfa.pruneDead()
        val dfa = nfa.nfaToDfa()
        dfa.compact()
        dfa.deduplicateFinals()
        if (storage != null) dfa.externalize(storage)
        dfa
    })

    fun run(query: String): Iterator<E> = Iterators.concat(
            Iterators.transform(automata.iterator()) { it!!.run(query) ?: Collections.emptyIterator<E>() })

    override fun close() {
        automata.forEach { it.close() }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

    private data class StateRequest(
            val componentsIndex: Int,
            val stringIndex: Int,
            val remainingJumps: Int,
            val justJumped: Boolean
    )

    private fun <E : Any> fromInput(
            nfa: Automaton<E>,
            cache: MutableObjectIntMap<StateRequest>,
            components: List<String>,
            finalIndex: Int,
            request: StateRequest
    ): Int {
        cache.getIfAbsent(request, -1).let { if (it != -1) return it }

        val builder = nfa.stateBuilder()
        cache.put(request, builder.state)

        val lastComponent = request.componentsIndex >= components.size - 1

        if (request.componentsIndex < components.size) {
            val lastInComponent = request.stringIndex == components[request.componentsIndex].length - 1

            // normal step
            val normalStepRequest = if (!lastInComponent)
                StateRequest(
                        componentsIndex = request.componentsIndex,
                        stringIndex = request.stringIndex + 1,
                        remainingJumps = request.remainingJumps,
                        justJumped = false
                )
            else
                StateRequest(
                        componentsIndex = request.componentsIndex + 1,
                        stringIndex = 0,
                        remainingJumps = request.remainingJumps,
                        justJumped = false
                )
            builder.addTransition(components[request.componentsIndex][request.stringIndex],
                    fromInput(nfa, cache, components, finalIndex, normalStepRequest))
        }

        if (!lastComponent) {
            if (request.remainingJumps > 0 || request.justJumped) {
                // jump to next component
                builder.addTransition(Automaton.EPSILON,
                        fromInput(nfa, cache, components, finalIndex, StateRequest(
                                componentsIndex = request.componentsIndex + 1,
                                stringIndex = 0,
                                remainingJumps =
                                if (request.justJumped) request.remainingJumps
                                else request.remainingJumps - 1,
                                justJumped = true
                        )))
            }
        }
        if (request.remainingJumps == 0 && !request.justJumped) {
            nfa.setFinal(builder.state, finalIndex)
        }
        builder.finish()
        return builder.state
    }
    }
}

