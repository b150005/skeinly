import SwiftUI
import Shared

struct CommentSectionView: View {
    let targetType: CommentTargetType
    let targetId: String
    private let viewModel: CommentSectionViewModel
    @StateObject private var observer: ViewModelObserver<CommentSectionState>
    @State private var commentText = ""

    init(targetType: CommentTargetType, targetId: String) {
        self.targetType = targetType
        self.targetId = targetId
        let vm = ViewModelFactory.commentSectionViewModel(
            targetType: targetType,
            targetId: targetId
        )
        self.viewModel = vm
        let wrapper = KoinHelperKt.wrapCommentSectionState(flow: vm.state)
        _observer = StateObject(wrappedValue: ViewModelObserver(wrapper: wrapper))
    }

    var body: some View {
        let state = observer.state

        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Comments")
                    .font(.headline)
                if state.isLoading {
                    ProgressView()
                        .scaleEffect(0.7)
                }
            }

            // Comment input
            HStack {
                TextField("Add a comment...", text: $commentText, axis: .vertical)
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
                .disabled(commentText.trimmingCharacters(in: .whitespaces).isEmpty || state.isSending)
            }

            if state.comments.isEmpty && !state.isLoading {
                Text("No comments yet")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(state.comments, id: \.id) { comment in
                    CommentRow(
                        comment: comment,
                        authorName: authorName(for: comment, in: state),
                        onDelete: {
                            viewModel.onEvent(event: CommentSectionEventDeleteComment(commentId: comment.id))
                        }
                    )
                }
            }
        }
    }

    private func authorName(for comment: Comment, in state: CommentSectionState) -> String {
        let user = state.authors[comment.authorId]
        return user?.displayName ?? "Unknown"
    }
}

private struct CommentRow: View {
    let comment: Comment
    let authorName: String
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(authorName)
                    .font(.caption)
                    .fontWeight(.semibold)
                Spacer()
                Text(formattedDate)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "trash")
                        .font(.caption2)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.red.opacity(0.7))
            }
            Text(comment.body)
                .font(.subheadline)
        }
        .padding(.vertical, 4)
    }

    private var formattedDate: String {
        let date = Date(timeIntervalSince1970: TimeInterval(comment.createdAt.epochSeconds))
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
