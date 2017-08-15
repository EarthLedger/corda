package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap

/**
 * Flow for ensuring that one or more counterparties are aware of all identities in a transaction for KYC purposes.
 * This is intended for use as a subflow of another flow.
 *
 * @return a mapping of well known identities to the confidential identities used in the transaction.
 */
// TODO: Can this be triggered automatically from [SendTransactionFlow]
@StartableByRPC
@InitiatingFlow
class IdentitySyncFlow(val otherSides: Set<Party>,
                       val tx: WireTransaction,
                       override val progressTracker: ProgressTracker) : FlowLogic<Unit>() {
    constructor(otherSide: Party, tx: WireTransaction) : this(setOf(otherSide), tx, tracker())

    companion object {
        object SYNCING_IDENTITIES : ProgressTracker.Step("Syncing identities")
        object AWAITING_ACKNOWLEDGMENT : ProgressTracker.Step("Awaiting acknowledgement")

        fun tracker() = ProgressTracker(SYNCING_IDENTITIES, AWAITING_ACKNOWLEDGMENT)
    }

    @Suspendable
    override fun call() {
        progressTracker.currentStep = SYNCING_IDENTITIES
        val states: List<ContractState> = (tx.inputs.map { serviceHub.loadState(it) }.requireNoNulls().map { it.data } + tx.outputs.map { it.data })
        val identities: List<AbstractParty> = states.flatMap { it.participants }
        // Filter participants down to the set of those not in the network map (are not well known)
        val confidentialIdentities = identities
                .filter { serviceHub.networkMapCache.getNodeByLegalIdentityKey(it.owningKey) == null }
                .toSet()
                .toList()
        val identityCertificates: Map<AbstractParty, PartyAndCertificate> = identities
                .map { Pair(it, serviceHub.identityService.certificateFromKey(it.owningKey)!!) }
                .toMap()

        otherSides.forEach { otherSide ->
            val requestedIdentities: List<AbstractParty> = sendAndReceive<List<AbstractParty>>(otherSide, confidentialIdentities).unwrap { req ->
                require(req.all { it in identityCertificates.keys }) { "${otherSide} requested a confidential identity not part of transaction ${tx.id}" }
                req
            }
            val sendIdentities: List<PartyAndCertificate> = requestedIdentities.map(identityCertificates::get).requireNoNulls()
            send(otherSide, sendIdentities)
        }

        progressTracker.currentStep = AWAITING_ACKNOWLEDGMENT
        otherSides.forEach { otherSide ->
            // Current unused return value from each party
            receive<Boolean>(otherSide)
        }
    }

}