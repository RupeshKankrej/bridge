import Foundation
import Security
import BridgeCore

/// KeyStorePort on the login Keychain: the P-256 identity key (generated inside the Keychain, never
/// exported), plus its certificate and the trusted-peer list as generic-password items. The
/// certificate is not added as a certificate item: the file-based Keychain ignores kSecAttrLabel
/// on certificates (it uses the CN), so it could not be found again reliably.
final class MacKeyStore: NSObject, KeyStorePort {
    private let keyTag = Data("dev.bridge.identity".utf8)
    private let keyLabel = "Bridge identity"
    private let certService = "dev.bridge.identity-certificate"
    private let trustService = "dev.bridge.trusted-peers"

    /// DER prefix that turns a raw P-256 point (what SecKey exports) into SubjectPublicKeyInfo:
    /// SEQUENCE { SEQUENCE { id-ecPublicKey, prime256v1 }, BIT STRING { ... } }.
    private static let p256SpkiHeader = Data([
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    ])

    func loadCertificate() -> KotlinByteArray? {
        readItem(service: certService).map { $0.kotlin }
    }

    func generateKey() throws -> KotlinByteArray {
        SecItemDelete([kSecClass: kSecClassKey, kSecAttrApplicationTag: keyTag] as CFDictionary)
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey([
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits: 256,
            kSecPrivateKeyAttrs: [kSecAttrIsPermanent: true, kSecAttrApplicationTag: keyTag, kSecAttrLabel: keyLabel],
        ] as CFDictionary, &error) else { throw cfError(error) }
        guard let publicKey = SecKeyCopyPublicKey(key),
              let point = SecKeyCopyExternalRepresentation(publicKey, &error) as Data?
        else { throw cfError(error) }
        return (Self.p256SpkiHeader + point).kotlin
    }

    func sign(data: KotlinByteArray) throws -> KotlinByteArray {
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(try privateKey(), .ecdsaSignatureMessageX962SHA256, data.data as CFData, &error) as Data?
        else { throw cfError(error) }
        return signature.kotlin
    }

    func saveCertificate(certificate: KotlinByteArray) throws {
        try writeItem(service: certService, data: certificate.data)
    }

    /// Certificate + private key as the SecIdentity that Network.framework's TLS presents.
    /// SecIdentityCreateWithCertificate finds the private key in the Keychain by its public key.
    func tlsIdentity() throws -> SecIdentity {
        guard let der = readItem(service: certService), let cert = SecCertificateCreateWithData(nil, der as CFData)
        else { throw KeychainError(status: errSecItemNotFound) }
        var identity: SecIdentity?
        try check(SecIdentityCreateWithCertificate(nil, cert, &identity))
        return identity!
    }

    // MARK: Trusted peers

    private struct StoredPeer: Codable {
        var fingerprint: String
        var name: String
        var host: String?
        var port: Int32
    }

    func trustedPeers() -> [TrustedPeer] {
        loadPeers().map { TrustedPeer(fingerprint: $0.fingerprint, name: $0.name, host: $0.host, port: $0.port) }
    }

    func saveTrustedPeer(peer: TrustedPeer) {
        storePeers(loadPeers().filter { $0.fingerprint != peer.fingerprint }
            + [StoredPeer(fingerprint: peer.fingerprint, name: peer.name, host: peer.host, port: peer.port)])
    }

    func removeTrustedPeer(fingerprint: String) {
        storePeers(loadPeers().filter { $0.fingerprint != fingerprint })
    }

    private func loadPeers() -> [StoredPeer] {
        guard let data = readItem(service: trustService) else { return [] }
        do { return try JSONDecoder().decode([StoredPeer].self, from: data) } catch {
            log.error("trusted peers unreadable: \(error)")
            return []
        }
    }

    private func storePeers(_ peers: [StoredPeer]) {
        do { try writeItem(service: trustService, data: try JSONEncoder().encode(peers)) } catch {
            log.error("saving trusted peers failed: \(error)")
        }
    }

    // MARK: Helpers

    private func readItem(service: String) -> Data? {
        var item: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecReturnData: true] as CFDictionary, &item)
        if status != errSecSuccess, status != errSecItemNotFound { log.error("reading \(service) failed: \(status)") }
        return item as? Data
    }

    private func writeItem(service: String, data: Data) throws {
        SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service] as CFDictionary)
        try check(SecItemAdd([kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: service, kSecValueData: data] as CFDictionary, nil))
    }

    private func privateKey() throws -> SecKey {
        var item: CFTypeRef?
        try check(SecItemCopyMatching([
            kSecClass: kSecClassKey, kSecAttrApplicationTag: keyTag, kSecAttrKeyClass: kSecAttrKeyClassPrivate, kSecReturnRef: true,
        ] as CFDictionary, &item))
        return item as! SecKey
    }
}

struct KeychainError: Error, CustomStringConvertible {
    let status: OSStatus
    var description: String { "Keychain error \(status): \(SecCopyErrorMessageString(status, nil) as String? ?? "unknown")" }
}

private func check(_ status: OSStatus) throws {
    if status != errSecSuccess { throw KeychainError(status: status) }
}

private func cfError(_ error: Unmanaged<CFError>?) -> Error {
    error?.takeRetainedValue() ?? KeychainError(status: errSecInternalError)
}
