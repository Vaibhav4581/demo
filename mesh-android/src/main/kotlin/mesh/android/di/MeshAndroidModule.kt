package mesh.android.di

import mesh.android.identity.KeystoreManager
import mesh.android.identity.NodeIdentityManager
import mesh.android.storage.MeshDatabase
import mesh.android.storage.RoomMessageStore
import mesh.storage.MessageStore
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

import mesh.android.storage.dao.MessageDao
import mesh.android.storage.dao.NodeDao
import mesh.android.storage.dao.OutboxDao
import mesh.android.storage.dao.RouteDao

val meshAndroidModule = module {
    single<KeystoreManager> { KeystoreManager(androidContext()) }
    single<NodeIdentityManager> { NodeIdentityManager(get<KeystoreManager>()) }
    single<MeshDatabase> {
        val keystore: KeystoreManager = get<KeystoreManager>()
        val passphrase = keystore.getOrCreateDatabasePassphrase()
        MeshDatabase.buildEncryptedDatabase(androidContext(), passphrase)
    }
    single<MessageDao> { get<MeshDatabase>().messageDao() }
    single<OutboxDao> { get<MeshDatabase>().outboxDao() }
    single<RouteDao> { get<MeshDatabase>().routeDao() }
    single<NodeDao> { get<MeshDatabase>().nodeDao() }
    single<MessageStore> { RoomMessageStore(get<MeshDatabase>()) }
    single<mesh.android.transport.TransportLogger> { mesh.android.transport.TransportLogger() }
    single<mesh.android.transport.NearbyTransport> {
        val identityManager: NodeIdentityManager = get()
        mesh.android.transport.NearbyTransport(
            context = androidContext(),
            localNodeId = identityManager.nodeId,
            logger = get()
        )
    }
    single<mesh.android.power.DutyCycleController> {
        mesh.android.power.DutyCycleController()
    }
    single<mesh.node.MeshNode> {
        val identityManager: NodeIdentityManager = get()
        val transport: mesh.android.transport.NearbyTransport = get()
        val messageStore: MessageStore = get()
        mesh.node.MeshNode(
            nodeId = identityManager.nodeId,
            transport = transport,
            messageStore = messageStore
        )
    }
}
