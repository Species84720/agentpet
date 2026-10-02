import Foundation

/// Optional Cloudflare relay delivery for hook events. The local Unix socket is
/// always primary; this best-effort mirror never changes a hook's exit status.
/// Configuration is intentionally file based because hook processes run outside
/// the app sandbox: `~/.agentpet/cloud-relay.json` contains a relay URL and an
/// *agent-role* device token, e.g. `{ "url": "https://relay.example.com", "token": "..." }`.
public enum CloudRelayPublisher {
    private struct Configuration: Decodable {
        let url: String
        let token: String
    }

    public static var configurationPath: String { AgentPetPaths.baseDir + "/cloud-relay.json" }

    /// Waits at most 700ms. Agent hooks must remain fast and fail open when the
    /// phone/cloud is unavailable; the desktop's durable local queue still
    /// handles its normal event delivery independently.
    public static func publish(_ event: AgentEvent, timeout: TimeInterval = 0.7) {
        guard let data = FileManager.default.contents(atPath: configurationPath),
              let config = try? JSONDecoder().decode(Configuration.self, from: data),
              let base = URL(string: config.url),
              base.scheme == "https", !config.token.isEmpty
        else { return }

        var request = URLRequest(url: base.appendingPathComponent("v1/events"))
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(config.token)", forHTTPHeaderField: "Authorization")
        request.httpBody = try? EventCoding.encoder.encode(event)
        guard request.httpBody != nil else { return }

        let finished = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { _, _, _ in finished.signal() }.resume()
        _ = finished.wait(timeout: .now() + timeout)
    }
}
