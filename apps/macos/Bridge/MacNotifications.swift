import Foundation
import UserNotifications
import BridgeCore

/// NotificationPort on UNUserNotificationCenter (spec §5.4). Shows tablet notifications grouped by app, with the
/// tablet's actions as buttons and an inline reply field, and reports clicks, replies and dismissals to core.
///
/// Muting while in Do Not Disturb or presenting is the OS's job: Focus silences banners from apps it doesn't allow,
/// and "Allow notifications when mirroring or sharing the display" is off by default (ADR 0009).
/// Never logs titles, text or replies.
final class MacNotifications: NSObject, NotificationPort, UNUserNotificationCenterDelegate {
    private let center = UNUserNotificationCenter.current()
    private let onProblem: (String) -> Void
    private var events: NotificationEvents?
    private var askedPermission = false
    /// Buttons belong to a category registered up front, not to the notification, so each distinct
    /// set of tablet actions gets its own category. setNotificationCategories replaces the whole set.
    private var categories: Set<UNNotificationCategory> = []
    private static let reply = "reply"

    init(onProblem: @escaping (String) -> Void) {
        self.onProblem = onProblem
        super.init()
        center.delegate = self
    }

    func watch(events: NotificationEvents) { self.events = events }

    func show(notification n: NotifPosted, icon: KotlinByteArray?) {
        // Spec §6: ask for Notifications permission at the first mirrored notification, not at launch.
        if !askedPermission {
            askedPermission = true
            center.requestAuthorization(options: [.alert, .sound]) { [onProblem] granted, _ in
                if !granted { DispatchQueue.main.async { onProblem("Allow notifications for Bridge in System Settings → Notifications to see the tablet's") } }
            }
        }
        let content = UNMutableNotificationContent()
        content.title = n.title
        content.subtitle = n.app
        content.body = n.text
        content.sound = .default
        content.threadIdentifier = n.app // groups by app in Notification Center
        content.categoryIdentifier = category(actions: n.actions, replyable: n.replyable)
        // The main icon is always Bridge's; the tablet app's icon shows as the thumbnail on the right.
        if let icon, let attachment = attachment(icon.data) { content.attachments = [attachment] }
        // Same identifier as an earlier request replaces it, matching Android's key semantics.
        center.add(UNNotificationRequest(identifier: n.key, content: content, trigger: nil)) { error in
            if let error { log.debug("notification not shown: \(error)") }
        }
    }

    func remove(key: String) {
        center.removeDeliveredNotifications(withIdentifiers: [key])
    }

    // NotificationPort's tablet half; the Mac never receives these.
    func action(key: String, index: Int32) {}
    func reply(key: String, text: String) {}

    private func category(actions: [String], replyable: Bool) -> String {
        let id = "notif:\(replyable):" + actions.joined(separator: "\u{1F}")
        guard !categories.contains(where: { $0.identifier == id }) else { return id }
        var buttons: [UNNotificationAction] = []
        if replyable {
            buttons.append(UNTextInputNotificationAction(identifier: Self.reply, title: "Reply", options: [],
                                                         textInputButtonTitle: "Send", textInputPlaceholder: "Reply"))
        }
        buttons += actions.enumerated().map { UNNotificationAction(identifier: "\($0.offset)", title: $0.element, options: []) }
        // customDismissAction: without it, closing a notification doesn't reach the delegate, so the tablet never hears of it.
        categories.insert(UNNotificationCategory(identifier: id, actions: buttons, intentIdentifiers: [], options: [.customDismissAction]))
        center.setNotificationCategories(categories)
        return id
    }

    /// UNNotificationAttachment moves the file into the notification store, so each notification gets its own copy.
    private func attachment(_ png: Data) -> UNNotificationAttachment? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).png")
        do {
            try png.write(to: url)
            return try UNNotificationAttachment(identifier: "icon", url: url)
        } catch {
            log.debug("notification icon skipped: \(error)")
            return nil
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let key = response.notification.request.identifier
        switch response.actionIdentifier {
        case UNNotificationDismissActionIdentifier:
            events?.dismissed(key: key)
        case Self.reply:
            if let text = (response as? UNTextInputNotificationResponse)?.userText, !text.isEmpty { events?.reply(key: key, text: text) }
        case UNNotificationDefaultActionIdentifier:
            break // a click on the body: nothing to do on the tablet
        default:
            if let index = Int32(response.actionIdentifier) { events?.action(key: key, index: index) }
        }
        completionHandler()
    }

    /// Without this, notifications are dropped whenever Bridge is the active app (e.g. the menu is open).
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }
}
