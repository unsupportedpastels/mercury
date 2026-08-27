import Foundation

struct IncomingShareAttachment: Identifiable, Hashable {
    let id: String
    let filename: String
    let mimeType: String?
    let data: Data
}

struct IncomingShareDraft: Identifiable, Hashable {
    let id: String
    let text: String
    let attachments: [IncomingShareAttachment]
    let notice: String?
}
