package net.corda.cordaupdates.transport.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.cordaupdates.transport.flows.Utils.toCordaException
import net.corda.cordaupdates.transport.flows.Utils.transporterFactory
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.StartableByService
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.unwrap
import org.apache.maven.repository.internal.MavenRepositorySystemUtils.newSession
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.spi.connector.transport.GetTask
import org.eclipse.aether.spi.connector.transport.Transporter
import java.net.URI
import java.util.*

/**
 * Fetches a resource from a remote repository. Used internally by Maven Resolver.
 *
 * @param resourceLocation resource location provided by Maven Resolver
 * @param repositoryHosterName x500Name of the repository hoster
 */
@StartableByService
@StartableByRPC
@InitiatingFlow
class GetResourceFlow(private val resourceLocation : String, private val repositoryHosterName : String) : FlowLogic<ByteArray>() {
    @Suspendable
    override fun call() : ByteArray {
        val repoHosterParty = serviceHub.identityService.wellKnownPartyFromX500Name(CordaX500Name.parse(repositoryHosterName))!!
        val repoHosterSession = initiateFlow(repoHosterParty)

        val payloadLength = repoHosterSession.sendAndReceive<Int>(resourceLocation).unwrap { it }

        val byteArray = ByteArray(payloadLength)
        var position = 0
        while (position < payloadLength) {
            val receivedBytes = repoHosterSession.receive<ByteArray>().unwrap { it }
            System.arraycopy(receivedBytes, 0, byteArray, position, receivedBytes.size)
            position += receivedBytes.size
        }
        return byteArray
    }
}

/**
 * This flows should exist at repository hoster's node. The flow fetches an artifact from a configured file- or http(s)- based repository,
 * splits it into chunks of [serviceHub.networkParameters.maxMessageSize] / 2 size and send back to the requester.
 *
 * The flow supports [SessionFilter]s to restrict unauthorised traffic.
 */
@InitiatedBy(GetResourceFlow::class)
class GetResourceFlowResponder(session : FlowSession) : AbstractRepositoryHosterResponder<Unit>(session) {
    @Suspendable
    override fun doCall() {
        val location = session.receive<String>().unwrap { it }
        val bytes = getArtifactBytes(location)

        val maxMessageSizeBytes = serviceHub.networkParameters.maxMessageSize / 2

        session.send(bytes.size)
        for (i in 0..bytes.size step maxMessageSizeBytes) {
            val chunk = Arrays.copyOfRange(bytes, i, Math.min(i + maxMessageSizeBytes, bytes.size ))
            session.send(chunk)
        }
    }

    private fun getArtifactBytes(location : String) : ByteArray {
        val getTask = GetTask(URI.create(location))
        val transporter = createTransporter()
        try {
            transporter.get(getTask)
        } catch (ex : Exception) {
            throw toCordaException(ex, transporter)
        }
        return getTask.dataBytes
    }

    private fun createTransporter() : Transporter {
        val configuration = serviceHub.cordaService(RepositoryHosterConfigurationService::class.java)
        val repository = RemoteRepository.Builder("remote", "default", configuration.remoteRepoUrl()).build()
        val transporterFactory = transporterFactory(repository.protocol)
        val repositorySession = newSession()
        return transporterFactory.newInstance(repositorySession, repository)!!
    }
}