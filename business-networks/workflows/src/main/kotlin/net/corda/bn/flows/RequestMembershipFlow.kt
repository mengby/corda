package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.MembershipContract
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.ReceiveFinalityFlow
import net.corda.core.flows.SignTransactionFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

@InitiatingFlow
@StartableByRPC
class RequestMembershipFlow(private val authorisedParty: Party, private val networkId: String) : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        // check whether party is authorised to initiate flow
        val auth = BNUtils.loadBNMemberAuth()
        val databaseService = serviceHub.cordaService(DatabaseService::class.java)
        val ourMembership = databaseService.getMembership(networkId, ourIdentity)?.state?.data
        if (ourMembership != null && !auth.canRequestMembership(ourMembership)) {
            throw FlowException("Initiator is not authorised to run ${javaClass.name} flow")
        }

        // send request to authorised member
        val authorisedPartySession = initiateFlow(authorisedParty)
        authorisedPartySession.send(networkId)

        // sign transaction
        val signResponder = object : SignTransactionFlow(authorisedPartySession) {
            override fun checkTransaction(stx: SignedTransaction) {
                val command = stx.tx.commands.single()
                if (command.value !is MembershipContract.Commands.Request) {
                    throw FlowException("Only Request command is allowed")
                }

                val membershipState = stx.tx.outputs.single().data as MembershipState
                if (ourIdentity != membershipState.identity) {
                    throw IllegalArgumentException("We have to be the member")
                }

                stx.toLedgerTransaction(serviceHub, false).verify()
            }
        }
        val stx = subFlow(signResponder)

        // receive finality flow
        return subFlow(ReceiveFinalityFlow(authorisedPartySession, stx.id))
    }
}

@InitiatingFlow
@InitiatedBy(RequestMembershipFlow::class)
class RequestMembershipFlowResponder(private val session: FlowSession) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() {
        // receive network ID
        val networkId = session.receive<String>().unwrap { it }

        // check whether party is authorised to modify membership
        val auth = BNUtils.loadBNMemberAuth()
        val databaseService = serviceHub.cordaService(DatabaseService::class.java)
        val ourMembership = databaseService.getMembership(networkId, ourIdentity)?.state?.data
        if (ourMembership != null && !auth.canActivateMembership(ourMembership)) {
            throw FlowException("Receiver is not authorised to modify membership")
        }

        val counterparty = session.counterparty
        if (databaseService.getMembership(networkId, counterparty) != null) {
            throw FlowException("Membership already exists")
        }

        // building transaction
        val authorisedMemberships = databaseService.getMembersAuthorisedToModifyMembership(networkId, auth)
        val observers = (authorisedMemberships.map { it.state.data.identity } - ourIdentity).toSet()
        val membershipState = MembershipState(
                identity = counterparty,
                networkId = networkId,
                status = MembershipStatus.PENDING,
                participants = (observers + ourIdentity + counterparty).toList()
        )
        val builder = TransactionBuilder(serviceHub.networkMapCache.notaryIdentities.first())
                .addOutputState(membershipState)
                .addCommand(MembershipContract.Commands.Request(), listOf(ourIdentity.owningKey, counterparty.owningKey))
        builder.verify(serviceHub)

        // signing transaction
        val selfSignedTransaction = serviceHub.signInitialTransaction(builder)
        val allSignedTransaction = subFlow(CollectSignaturesFlow(selfSignedTransaction, listOf(session)))

        // finalise transaction
        val observerSessions = observers.map { initiateFlow(it) }.toSet()
        subFlow(FinalityFlow(allSignedTransaction, observerSessions + session))
    }
}

@InitiatedBy(RequestMembershipFlowResponder::class)
class RequestMembershipObserverFlow(private val session: FlowSession) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() {
        subFlow(ReceiveFinalityFlow(session))
    }
}