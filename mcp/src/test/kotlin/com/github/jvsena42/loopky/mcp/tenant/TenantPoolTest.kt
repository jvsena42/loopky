package com.github.jvsena42.loopky.mcp.tenant

import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.mcp.FakeSecrets
import com.github.jvsena42.loopky.mcp.FakeTenant
import com.github.jvsena42.loopky.mcp.FakeTenantFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Isolation is the property: a call for one pubky runs on the graph opened from that pubky's
 * secret, under every schedule and across eviction. Everything else here is the bounds.
 */
class TenantPoolTest {

    private val secrets = FakeSecrets(mapOf("alice" to "secret-alice", "bob" to "secret-bob"))
    private val factory = FakeTenantFactory()
    private var now = 0L

    private fun pool(limits: TenantLimits = TenantLimits()) = TenantPool(factory, secrets, limits) { now }

    private suspend fun TenantPool.pubkyOf(caller: String): String = withTenant(caller) { it.session.identity.pubky }

    @Test
    fun `a credential that opens someone else's session is refused and closed`() = runBlocking {
        val swapped = FakeSecrets(mapOf("alice" to "secret-bob"))
        val pool = TenantPool(factory, swapped) { now }
        assertFailsWith<TenantRefusal.NotLinked> { pool.pubkyOf("alice") }
        assertTrue((factory.opened.single() as FakeTenant).closed)
        assertTrue(pool.openTenants().isEmpty())
    }

    @Test
    fun `a re-link with a new secret reopens the graph instead of serving the old grant`() = runBlocking {
        val pool = pool()
        val first = pool.withTenant("alice") { it } as FakeTenant
        secrets.secrets["alice"] = "secret-alice#2"
        val second = pool.withTenant("alice") { it } as FakeTenant
        assertEquals("secret-alice#2", second.secret)
        assertTrue(first.closed, "the graph holding the replaced grant is closed")
    }

    @Test
    fun `a refusal of a secret that was replaced meanwhile never unlinks the new one`() = runBlocking {
        val pool = pool()
        pool.withTenant("alice") {
            // Re-linked while this call ran on the old grant, which the re-link then revoked.
            secrets.secrets["alice"] = "secret-alice#2"
            pool.revoke("alice")
        }
        assertTrue(secrets.revoked.isEmpty(), "the new link was reported revoked")
        assertTrue(!pool.isRevoked("alice"))
        assertEquals("secret-alice#2", (pool.withTenant("alice") { it } as FakeTenant).secret)
    }

    @Test
    fun `each principal gets the graph opened from its own secret`() = runBlocking {
        val pool = pool()
        assertEquals("alice", pool.pubkyOf("alice"))
        assertEquals("bob", pool.pubkyOf("bob"))
        assertEquals("alice", pool.pubkyOf("alice"))
        assertEquals(2, factory.opens.get(), "a graph is opened once per user, not per call")
        assertEquals(listOf("secret-alice", "secret-bob"), factory.opened.map { (it as FakeTenant).secret })
    }

    @Test
    fun `under concurrency no call ever sees another principal's graph`() = runBlocking {
        val pool = pool(TenantLimits(maxConcurrentCalls = 4, busyWait = 5.minutes))
        val seen = ConcurrentHashMap<String, MutableSet<String>>()
        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(40) { index ->
                    val caller = if (index % 2 == 0) "alice" else "bob"
                    launch {
                        pool.withTenant(caller) { tenant ->
                            yield()
                            seen.getOrPut(caller) { ConcurrentHashMap.newKeySet() } += tenant.session.identity.pubky
                        }
                    }
                }
            }
        }
        assertEquals(setOf("alice"), seen["alice"]?.toSet())
        assertEquals(setOf("bob"), seen["bob"]?.toSet())
    }

    @Test
    fun `an evicted graph is closed and the reopened one is fresh, from the same principal's secret`() = runBlocking {
        val pool = pool(TenantLimits(maxTenants = 1))
        val first = pool.withTenant("alice") { it }
        pool.withTenant("bob") { it }
        assertTrue((first as FakeTenant).closed, "alice's graph closed when bob's took the slot")
        assertEquals(setOf("bob"), pool.openTenants())
        val again = pool.withTenant("alice") { it }
        assertNotSame(first, again)
        assertEquals("secret-alice", (again as FakeTenant).secret)
        assertEquals("alice", again.session.identity.pubky)
    }

    @Test
    fun `a graph in use is never evicted from under its call`() = runBlocking {
        val pool = pool(TenantLimits(maxTenants = 1, busyWait = 5.minutes))
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val aliceCall = async(Dispatchers.Default) {
            pool.withTenant("alice") { tenant ->
                holding.complete(Unit)
                release.await()
                tenant
            }
        }
        holding.await()
        val bob = pool.withTenant("bob") { it }
        release.complete(Unit)
        val alice = aliceCall.await()
        assertTrue(!(alice as FakeTenant).closed, "closed while its call was running")
        assertEquals("bob", bob.session.identity.pubky)
    }

    @Test
    fun `idle graphs are closed on the next call, and the user gets a fresh one`() = runBlocking {
        val pool = pool(TenantLimits(idleTtl = 1.minutes))
        val first = pool.withTenant("alice") { it } as FakeTenant
        now += 2.minutes.inWholeMilliseconds
        pool.sweep()
        assertTrue(first.closed)
        assertEquals(emptySet(), pool.openTenants())
        assertNotSame(first, pool.withTenant("alice") { it })
    }

    // -- bounds ----------------------------------------------------------------------------------

    @Test
    fun `a second call for the same user waits its bound and is then refused as busy`() = runBlocking {
        val pool = pool(TenantLimits(busyWait = 50.milliseconds))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            pool.withTenant("alice") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        assertFailsWith<TenantRefusal.UserBusy> { pool.withTenant("alice") { } }
        // Another user is not waiting on alice.
        assertEquals("bob", pool.pubkyOf("bob"))
        release.complete(Unit)
        first.await()
        assertEquals("alice", pool.pubkyOf("alice"))
    }

    @Test
    fun `the global cap refuses the call past it`() = runBlocking {
        secrets.secrets["carol"] = "secret-carol"
        val pool = pool(TenantLimits(maxConcurrentCalls = 2, busyWait = 50.milliseconds))
        val release = CompletableDeferred<Unit>()
        val entered = ConcurrentHashMap.newKeySet<String>()
        val running = listOf("alice", "bob").map { caller ->
            async(Dispatchers.Default) {
                pool.withTenant(caller) {
                    entered += caller
                    release.await()
                }
            }
        }
        while (entered.size < 2) delay(5)
        assertFailsWith<TenantRefusal.ServerBusy> { pool.withTenant("carol") { } }
        release.complete(Unit)
        running.awaitAll()
        assertEquals("carol", pool.pubkyOf("carol"))
    }

    @Test
    fun `a call past its timeout is stopped and reported`() = runBlocking {
        val pool = pool(TenantLimits(callTimeout = 50.milliseconds))
        assertFailsWith<TenantRefusal.TimedOut> { pool.withTenant("alice") { delay(10.minutes) } }
        assertEquals("alice", pool.pubkyOf("alice"), "the graph is still usable afterwards")
    }

    // -- the credential ----------------------------------------------------------------------------

    @Test
    fun `a principal with no linked secret is refused, and nothing is opened`() = runBlocking {
        assertFailsWith<TenantRefusal.NotLinked> { pool().withTenant("mallory") { } }
        assertEquals(0, factory.opens.get())
    }

    @Test
    fun `revoking reports the secret, closes the graph and refuses the next call`() = runBlocking {
        val pool = pool()
        val tenant = pool.withTenant("alice") { tenant ->
            pool.revoke("alice")
            tenant
        } as FakeTenant
        assertTrue(tenant.closed, "closed once the revoking call returned")
        assertEquals(listOf("alice"), secrets.revoked)
        assertTrue(pool.isRevoked("alice"))
        assertFailsWith<TenantRefusal.NotLinked> { pool.withTenant("alice") { } }
        assertEquals(1, factory.opens.get(), "the refused secret was not opened again")
    }

    @Test
    fun `a secret the factory cannot adopt is reported revoked`() = runBlocking {
        val refusing = FakeTenantFactory { _, _ -> throw PubkyError("Session rejected: 401 Unauthorized") }
        val pool = TenantPool(refusing, secrets, TenantLimits()) { now }
        assertFailsWith<TenantRefusal.Revoked> { pool.withTenant("alice") { } }
        assertEquals(listOf("alice"), secrets.revoked)
        assertTrue(pool.isRevoked("alice"))
    }

    @Test
    fun `linking again with a different secret clears the revocation`() = runBlocking {
        val pool = pool()
        pool.revoke("alice")
        assertTrue(pool.isRevoked("alice"))
        secrets.secrets["alice"] = "secret-alice"
        assertTrue(pool.isRevoked("alice"), "the same secret is still the refused one")
        secrets.secrets["alice"] = "secret-alice#2"
        assertTrue(!pool.isRevoked("alice"))
        assertEquals("secret-alice#2", (pool.withTenant("alice") { it } as FakeTenant).secret)
    }
}
