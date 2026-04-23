import SwiftUI
import Shared

struct CommentSectionView: View {
    let targetType: CommentTargetType
    let targetId: String
    @StateObject private var holder: ScopedViewModel<CommentSectionViewModel, CommentSectionState>
    @State private var commentText = ""
    @State private var commentToDeleteId: String?

    private var viewModel: CommentSectionViewModel { holder.viewModel }

    init(targetType: CommentTargetType, targetId: String) {
        self.targetType = targetType
        self.targetId = targetId
        let vm = ViewModelFactory.commentSectionViewModel(
            targetType: targetType,
            targetId: targetId
        )
        let wrapper = KoinHelperKt.wrapCommentSectionState(flow: vm.state)
        _holder = StateObject(wrappedValue: ScopedViewModel(viewModel: vm, wrapper: wrapper))
    }

    var body: some View {
        let state = holder.state

        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(LocalizedStringKey("label_comments_section"))
                    .font(.headline)
                if state.isLoading {
                    ProgressView()
                        .scaleEffect(0.7)
                }
            }

            // Comment input
            HStack {
                TextField(
                    LocalizedStringKey("hint_add_comment"),
                    text: $commentText,
                    axis: .vertical
                )
                .lineLimit(1...3)
                .textFieldStyle(.roundedBorder)

                Button {
                    viewModel.onEvent(event: CommentSectionEventPostComment(body: commentText))
                    commentText = ""
                } label: {
                    if state.isSending {
                        ProgressView()
                    } else {
                        Image(systemName: "paperplane.fill")
                    }
                }
                .accessibilityLabel(LocalizedStringKey("action_send_comment"))
                .disabled(commentText.trimmingCharacters(in: .whitespaces).isEmpty || state.isSending)
            }

            if state.comments.isEmpty && !state.isLoading {
                Text(LocalizedStringKey("state_no_comments"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(state.comments, id: \.id) { comment in
                    CommentRow(
                        comment: comment,
                        authorName: authorName(for: comment, in: state),
                        onDelete: {
                            commentToDeleteId = comment.id
                        }
                    )
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("commentSection")
        .confirmationDialog(
            LocalizedStringKey("dialog_delete_comment_title"),
            isPresented: Binding(
                get: { commentToDeleteId != nil },
                set: { if !$0 { commentToDeleteId = nil } }
            ),
            titleVisibility: .visible,
            presenting: commentToDeleteId
        ) { commentId in
            Button(role: .destructive) {
                viewModel.onEvent(event: CommentSectionEventDeleteComment(commentId: commentId))
                commentToDeleteId = nil
            } label: {
                Text(LocalizedStringKey("action_delete"))
            }
            Button(role: .cancel) {
                commentToDeleteId = nil
            } label: {
                Text(LocalizedStringKey("action_cancel"))
            }
        } message: { _ in
            Text(LocalizedStringKey("dialog_delete_comment_body"))
        }
    }

    private func authorName(for comment: Comment, in state: CommentSectionState) -> String {
        // Null-author fallback unified with Android on `label_someone` (33.1.7
        // ActivityFeed precedent). Previously iOS rendered "Unknown" as a bare
        // English literal.
        let user = state.authors[comment.authorId]
        if let displayName = user?.displayName, !displayName.isEmpty {
            return displayName
        }
        return NSLocalizedString("label_someone", comment: "")
    }
}

private struct CommentRow: View {
    let comment: Comment
    let authorName: String
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(verbatim: authorName)
                    .font(.caption)
                    .fontWeight(.semibold)
                Spacer()
                Text(verbatim: formattedDate)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "trash")
                        .font(.caption2)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.red.opacity(0.7))
                .accessibilityLabel(LocalizedStringKey("action_delete_comment"))
            }
            Text(verbatim: comment.body)
                .font(.subheadline)
        }
        .padding(.vertical, 4)
    }

    private var formattedDate: String {
        let date = Date(timeIntervalSince1970: TimeInterval(comment.createdAt.epochSeconds))
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
