package net.corda.businessnetworks.membership.states

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import java.time.Instant

/**
 * Contracts that verifies evolution of the Membership States
 */
class MembershipContract : Contract {
    companion object {
        const val CONTRACT_NAME = "net.corda.businessnetworks.membership.states.MembershipContract"
    }

    open class Commands : CommandData, TypeOnlyCommandData() {
        class Request : Commands()
        class Amend : Commands()
        class Suspend : Commands()
        class Activate : Commands()
    }

    override fun verify(tx : LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        val output = tx.outputs.single()
        val outputState = output.data as MembershipState<*>

        requireThat {
            "Modified date has to be greater or equal to the issued date" using (outputState.modified >= outputState.issued)
            "Both BNO and member have to be participants" using (outputState.participants.toSet() == setOf(outputState.member, outputState.bno))
            "Output state has to be validated with $CONTRACT_NAME" using (output.contract == CONTRACT_NAME)
            if (!tx.inputs.isEmpty()) {
                val input = tx.inputs.single()
                val inputState = input.state.data as MembershipState<*>
                "Participants of input and output states should be the same" using (outputState.participants.toSet() == input.state.data.participants.toSet())
                "Input state has to be validated with $CONTRACT_NAME" using (input.state.contract == CONTRACT_NAME)
                "Input and output states should have the same issued dates" using (inputState.issued == outputState.issued)
                "Input and output states should have the same linear IDs" using (inputState.linearId == outputState.linearId)
                "Output state's modified timestamp should be greater than input's" using (outputState.modified > inputState.modified)
            }
        }

        when (command.value) {
            is Commands.Request -> requireThat {
                "Both BNO and member have to sign a membership request transaction" using (command.signers.toSet() == outputState.participants.map { it.owningKey }.toSet() )
                "Membership request transaction shouldn't contain any inputs" using (tx.inputs.isEmpty())
                "Membership request transaction should contain an output state in PENDING status" using (outputState.isPending())
            }
            is Commands.Suspend -> requireThat {
                val inputStateAndRef = tx.inputs.single()
                val inputState = inputStateAndRef.state.data as MembershipState<*>
                "Only BNO should sign a revocation transaction" using (command.signers.toSet() == setOf(outputState.bno.owningKey))
                "Input state of a revocation transaction shouldn't be already revoked" using (!inputState.isSuspended())
                "Output state of a revocation transaction should be revoked" using (outputState.isSuspended())
                "Input and output states of a revocation transaction should have the same metadata" using (inputState.membershipMetadata == outputState.membershipMetadata)
            }
            is Commands.Activate -> requireThat {
                val inputStateAndRef = tx.inputs.single()
                val inputState = inputStateAndRef.state.data as MembershipState<*>
                "Only BNO should sign a membership activation transaction" using (command.signers.toSet() == setOf(outputState.bno.owningKey))
                "Input state of a membership activation transaction shouldn't be already active" using (!inputState.isActive())
                "Output state of a membership activation transaction should be active" using (outputState.isActive())
                "Input and output states of a membership activation transaction should have the same metadata" using (inputState.membershipMetadata == outputState.membershipMetadata)
            }
            is Commands.Amend -> requireThat {
                val inputStateAndRef = tx.inputs.single()
                val inputState = inputStateAndRef.state.data as MembershipState<*>
                "Both BNO and member have to sign a metadata amendment transaction" using (command.signers.toSet() == outputState.participants.map { it.owningKey }.toSet() )
                "Both input and output states of a metadata amendment transaction should be active" using (inputState.isActive() && outputState.isActive())
                "Input and output states of an amendment transaction should have different membership metadata" using (inputState.membershipMetadata != outputState.membershipMetadata)
            }
            else -> throw IllegalArgumentException("Unsupported command ${command.value}")
        }
    }

}


/**
 * This state represents a membership on the ledger. It supports user defined extensions via [membershipMetadata].
 * Users can associate any custom metadata objects with their Membership States and store it on the ledger. For example:
 *
 * @CordaSerializable
 * data class MyMembershipMetadata (val role : String, val email : String, val address : String)
 *
 * @param member identity of a member
 * @param bno identity of the BNO
 * @param issued timestamp when the state has been issued
 * @param modified timestamp when the state has been modified the last time
 * @param status status of the state, i.e. ACTIVE, SUSPENDED, PENDING etc.
 */
data class MembershipState<out T>(val member : Party,
                              val bno : Party,
                              val membershipMetadata : T,
                              val issued : Instant = Instant.now(),
                              val modified : Instant = issued,
                              val status : MembershipStatus = MembershipStatus.PENDING,
                              override val linearId : UniqueIdentifier = UniqueIdentifier()) : LinearState, QueryableState {
    override fun generateMappedObject(schema : MappedSchema) : PersistentState {
        return when (schema) {
            is MembershipStateSchemaV1 -> MembershipStateSchemaV1.PersistentMembershipState(
                    member = this.member,
                    status = this.status
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }
    override fun supportedSchemas() = listOf(MembershipStateSchemaV1)
    override val participants = listOf(bno, member)
    fun isSuspended() = status == MembershipStatus.SUSPENDED
    fun isPending() = status == MembershipStatus.PENDING
    fun isActive() = status == MembershipStatus.ACTIVE
}

/**
 * Statuses that membership can go through.
 *
 * [PENDING] - newly submitted state, haven't been approved yet. Pending members can't transact on the Business Network
 * [ACTIVE] - active members can transact on the Business Network
 * [SUSPENDED] - suspended members can't transact on the Business Network. Suspended members can be activated back.
 */
@CordaSerializable
enum class MembershipStatus {
    PENDING, ACTIVE, SUSPENDED
}

/**
 * Simple metadata, that assigns a role with membership state
 */
@CordaSerializable
data class SimpleMembershipMetadata(val role : String)