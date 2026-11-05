import SwiftUI
import UniformTypeIdentifiers

/// Full JSON backup: history, positions, watched marks, bookmarks, per-title player settings
/// (dub, quality, delays), favourite dubs and app settings. Account tokens are deliberately left out.
struct AnixQBackup: Codable {
    var format = "anixq-backup"
    var version = 1
    var appVersion: String
    var created: Date
    var device: String
    var library: LibraryBackup
    var settings: [String: SettingValue]

    /// UserDefaults values in plain JSON.
    enum SettingValue: Codable {
        case bool(Bool), int(Int), double(Double), string(String), strings([String]), ints([Int]), data(Data)

        init?(_ any: Any) {
            switch any {
            case let v as String: self = .string(v)
            case let v as Int: self = .int(v)
            case let v as Double: self = .double(v)
            case let v as [String]: self = .strings(v)
            case let v as [Int]: self = .ints(v)
            case let v as Data: self = .data(v)
            default: return nil
            }
        }

        var value: Any {
            switch self {
            case .bool(let v): return v
            case .int(let v): return v
            case .double(let v): return v
            case .string(let v): return v
            case .strings(let v): return v
            case .ints(let v): return v
            case .data(let v): return v
            }
        }
    }
}

@MainActor
enum BackupManager {
    /// Settings worth carrying over (no tokens, cookies or lock state).
    private static let prefixes = ["accent", "uiStyle", "appearance", "adblock", "subs.", "dubs.", "autodl.", "download.",
                                   "gesture.", "player.", "dl.", "notify.", "titleSettings", "search.recent", "icloud.enabled",
                                   "appIcon", "update."]

    static func make(library: LibraryStore) -> AnixQBackup {
        var settings: [String: AnixQBackup.SettingValue] = [:]
        for (k, v) in UserDefaults.standard.dictionaryRepresentation() where prefixes.contains(where: { k.hasPrefix($0) }) {
            // NSNumber booleans must stay booleans.
            if let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() {
                settings[k] = .bool(n.boolValue)
            } else if let n = v as? NSNumber, CFNumberIsFloatType(n) {
                settings[k] = .double(n.doubleValue)
            } else if let n = v as? NSNumber {
                settings[k] = .int(n.intValue)
            } else if let sv = AnixQBackup.SettingValue(v) {
                settings[k] = sv
            }
        }
        return AnixQBackup(appVersion: UpdateChecker.installedVersion, created: Date(), device: UIDevice.current.model,
                           library: library.backupPayload(), settings: settings)
    }

    static func encode(_ b: AnixQBackup) throws -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        e.dateEncodingStrategy = .iso8601
        return try e.encode(b)
    }

    static func decode(_ data: Data) throws -> AnixQBackup {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        let b = try d.decode(AnixQBackup.self, from: data)
        guard b.format == "anixq-backup" else { throw LibError.server("Это не резервная копия AnixQ") }
        return b
    }

    static func restore(_ b: AnixQBackup, library: LibraryStore) {
        library.restore(b.library)
        for (k, v) in b.settings { UserDefaults.standard.set(v.value, forKey: k) }
        Theme.configureAppearance()
        DebugLog.log("backup: restored \(b.library.history.count) history, \(b.library.bookmarks.count) bookmarks, \(b.settings.count) settings")
    }

    static func fileName(_ date: Date = Date()) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd_HH-mm"
        return "AnixQ-backup-\(f.string(from: date)).json"
    }

    // MARK: Automatic copies

    /// App's own Documents/Backups (visible in Files → On My iPhone → AnixQ).
    static var localFolder: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Backups", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static var autoEnabled: Bool {
        get { UserDefaults.standard.object(forKey: "backup.auto") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "backup.auto") }
    }

    static var lastAuto: Date? {
        get { UserDefaults.standard.object(forKey: "backup.last") as? Date }
        set { UserDefaults.standard.set(newValue, forKey: "backup.last") }
    }

    /// A folder the user picked (e.g. iCloud Drive/AnixQ) — survives reinstalling the IPA.
    static var externalFolderName: String? {
        resolveExternal().map { $0.lastPathComponent }
    }

    static func setExternalFolder(_ url: URL?) {
        guard let url else { UserDefaults.standard.removeObject(forKey: "backup.folder"); return }
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        if let data = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
            UserDefaults.standard.set(data, forKey: "backup.folder")
        }
    }

    private static func resolveExternal() -> URL? {
        guard let data = UserDefaults.standard.data(forKey: "backup.folder") else { return nil }
        var stale = false
        return try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
    }

    /// Once a day (when the app goes to the background): a copy locally and in the chosen folder,
    /// keeping the last five.
    static func autoBackupIfNeeded(library: LibraryStore, force: Bool = false) {
        guard autoEnabled, force || (lastAuto.map { Date().timeIntervalSince($0) > 20 * 3600 } ?? true),
              !library.history.isEmpty || !library.bookmarks.isEmpty,
              let data = try? encode(make(library: library)) else { return }
        let name = fileName()
        write(data, name: name, to: localFolder)
        if let ext = resolveExternal() {
            let access = ext.startAccessingSecurityScopedResource()
            write(data, name: name, to: ext)
            if access { ext.stopAccessingSecurityScopedResource() }
        }
        lastAuto = Date()
        DebugLog.log("backup: auto copy \(name) \(data.count / 1024) KB")
    }

    private static func write(_ data: Data, name: String, to folder: URL) {
        do {
            try data.write(to: folder.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            let old = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
            let backups = old.filter { $0.lastPathComponent.hasPrefix("AnixQ-backup-") }.sorted { $0.lastPathComponent > $1.lastPathComponent }
            for f in backups.dropFirst(5) { try? FileManager.default.removeItem(at: f) }
        } catch {
            DebugLog.log("backup: write to \(folder.lastPathComponent) failed \(error.localizedDescription)")
        }
    }
}

struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    let data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct BackupView: View {
    @EnvironmentObject private var library: LibraryStore
    @State private var exportDoc: BackupDocument?
    @State private var exporting = false
    @State private var importing = false
    @State private var pickingFolder = false
    @State private var pending: AnixQBackup?
    @State private var message: String?
    @State private var auto = BackupManager.autoEnabled
    @State private var folder = BackupManager.externalFolderName

    var body: some View {
        Form {
            Section {
                Button {
                    do {
                        exportDoc = BackupDocument(data: try BackupManager.encode(BackupManager.make(library: library)))
                        exporting = true
                    } catch { message = error.localizedDescription }
                } label: { Label("Сохранить копию в Файлы…", systemImage: "square.and.arrow.down.on.square") }
                Button { importing = true } label: { Label("Восстановить из копии…", systemImage: "clock.arrow.circlepath") }
            } footer: {
                Text("В копии: история и позиции в сериях, закладки, отметки серий, выбранные озвучки и настройки плеера для каждого тайтла, любимые озвучки, настройки приложения. Пароли и токены входа не сохраняются — после восстановления войдите в аккаунты заново.")
            }
            Section {
                Toggle("Автокопия раз в день", isOn: $auto).onChange(of: auto) { BackupManager.autoEnabled = $0 }
                Button { pickingFolder = true } label: {
                    LabeledContent("Папка для копий", value: folder ?? "только на устройстве")
                }
                if folder != nil {
                    Button("Не копировать в папку", role: .destructive) {
                        BackupManager.setExternalFolder(nil)
                        folder = nil
                    }
                }
                if let last = BackupManager.lastAuto {
                    LabeledContent("Последняя", value: last.formatted(date: .abbreviated, time: .shortened))
                }
                Button("Сделать копию сейчас") {
                    BackupManager.autoBackupIfNeeded(library: library, force: true)
                    message = "Копия сохранена"
                }
            } header: {
                Text("Автоматически")
            } footer: {
                Text("Копии лежат в «Файлы» → «На iPhone» → AnixQ → Backups (последние 5). Чтобы они пережили переустановку IPA, выберите папку в iCloud Drive — например, создайте там папку AnixQ.")
            }
            if let message { Text(message).font(.footnote).foregroundStyle(.secondary) }
        }
        .styledList()
        .navigationTitle("Резервная копия")
        .fileExporter(isPresented: $exporting, document: exportDoc, contentType: .json,
                      defaultFilename: BackupManager.fileName()) { result in
            if case .success = result { message = "Копия сохранена" }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            do {
                pending = try BackupManager.decode(Data(contentsOf: url))
            } catch {
                message = "Не удалось прочитать копию: \(error.localizedDescription)"
            }
        }
        .background {
            // A second fileImporter on the same view is ignored by SwiftUI on iOS 16, so the folder
            // picker lives on a background view.
            Color.clear.fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
                guard case .success(let url) = result else { return }
                BackupManager.setExternalFolder(url)
                folder = BackupManager.externalFolderName
                BackupManager.autoBackupIfNeeded(library: library, force: true)
            }
        }
        .confirmationDialog("Восстановить копию?", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }),
                            titleVisibility: .visible) {
            Button("Восстановить", role: .destructive) {
                if let p = pending {
                    BackupManager.restore(p, library: library)
                    message = "Восстановлено: \(p.library.history.count) в истории, \(p.library.bookmarks.count) закладок"
                }
                pending = nil
            }
        } message: {
            if let p = pending {
                Text("Копия от \(p.created.formatted(date: .abbreviated, time: .shortened)), версия \(p.appVersion): \(p.library.history.count) в истории, \(p.library.bookmarks.count) закладок. Текущая медиатека будет заменена.")
            }
        }
    }
}
