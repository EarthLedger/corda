package net.corda.bank

import net.corda.contracts.asset.Cash
import net.corda.core.internal.concurrent.transpose
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.ServiceInfo
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.finance.DOLLARS
import net.corda.flows.CashIssueFlow
import net.corda.flows.CashPaymentFlow
import net.corda.node.services.startFlowPermission
import net.corda.node.services.transactions.SimpleNotaryService
import net.corda.nodeapi.User
import net.corda.testing.*
import net.corda.testing.driver.driver
import org.junit.Test

class BankOfCordaRPCClientTest {
    @Test
    fun `issuer flow via RPC`() {
        driver(dsl = {
            val bocManager = User("bocManager", "password1", permissions = setOf(
                    startFlowPermission<CashIssueFlow>(),
                    startFlowPermission<CashPaymentFlow>()))
            val bigCorpCFO = User("bigCorpCFO", "password2", permissions = emptySet())
            val (nodeBankOfCorda, nodeBigCorporation) = listOf(
                    startNode(BOC.name, setOf(ServiceInfo(SimpleNotaryService.type)), listOf(bocManager)),
                    startNode(BIGCORP_LEGAL_NAME, rpcUsers = listOf(bigCorpCFO))
            ).transpose().getOrThrow()

            // Bank of Corda RPC Client
            val bocClient = nodeBankOfCorda.rpcClientToNode()
            val bocProxy = bocClient.start("bocManager", "password1").proxy

            // Big Corporation RPC Client
            val bigCorpClient = nodeBigCorporation.rpcClientToNode()
            val bigCorpProxy = bigCorpClient.start("bigCorpCFO", "password2").proxy

            // Register for Bank of Corda Vault updates
            val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val vaultUpdatesBoc = bocProxy.vaultTrackByCriteria<Cash.State>(Cash.State::class.java, criteria).updates

            // Register for Big Corporation Vault updates
            val vaultUpdatesBigCorp = bigCorpProxy.vaultTrackByCriteria<Cash.State>(Cash.State::class.java, criteria).updates

            // Kick-off actual Issuer Flow
            val anonymous = true
            bocProxy.startFlow(
                    ::CashIssueFlow,
                    1000.DOLLARS,
                    BIG_CORP_PARTY_REF,
                    nodeBankOfCorda.nodeInfo.notaryIdentity).returnValue.getOrThrow()
            bocProxy.startFlow(
                    ::CashPaymentFlow,
                    1000.DOLLARS,
                    nodeBigCorporation.nodeInfo.legalIdentity,
                    anonymous).returnValue.getOrThrow()

            // Check Bank of Corda Vault Updates
            vaultUpdatesBoc.expectEvents {
                sequence(
                        // ISSUE
                        expect { update ->
                            require(update.consumed.isEmpty()) { "Expected 0 consumed states, actual: $update" }
                            require(update.produced.size == 1) { "Expected 1 produced states, actual: $update" }
                        },
                        // MOVE
                        expect { update ->
                            require(update.consumed.size == 1) { "Expected 1 consumed states, actual: $update" }
                            require(update.produced.isEmpty()) { "Expected 0 produced states, actual: $update" }
                        }
                )
            }

            // Check Big Corporation Vault Updates
            vaultUpdatesBigCorp.expectEvents {
                sequence(
                        // MOVE
                        expect { update ->
                            require(update.consumed.isEmpty()) { update.consumed.size }
                            require(update.produced.size == 1) { update.produced.size }
                        }
                )
            }
        }, isDebug = true)
    }
}
