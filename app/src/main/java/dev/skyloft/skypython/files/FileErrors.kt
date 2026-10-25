package dev.skyloft.skypython.files

import android.content.Context
import androidx.annotation.StringRes
import dev.skyloft.skypython.R

@StringRes
fun NameProblem.messageRes(): Int = when (this) {
    NameProblem.Empty -> R.string.name_error_empty
    NameProblem.Dots -> R.string.name_error_dots
    NameProblem.Separator -> R.string.name_error_separator
    NameProblem.InvalidChar -> R.string.name_error_invalid_char
    NameProblem.TooLong -> R.string.name_error_too_long
}

/** A failed [Workspace] operation as a message in the language of [context]. */
fun describeFileError(context: Context, e: Throwable): String = when (e) {
    is InvalidNameException -> context.getString(e.problem.messageRes())
    is WorkspaceException -> when (e.reason) {
        WorkspaceException.Reason.AlreadyExists -> context.getString(R.string.files_error_already_exists, e.name)
        WorkspaceException.Reason.CannotCreateFolder -> context.getString(R.string.files_error_create_folder, e.name)
        WorkspaceException.Reason.FolderMissing -> context.getString(R.string.files_error_folder_missing)
        WorkspaceException.Reason.DoesNotExist -> context.getString(R.string.files_error_does_not_exist, e.name)
        WorkspaceException.Reason.CannotRename -> context.getString(R.string.files_error_rename, e.name)
        WorkspaceException.Reason.CannotDelete -> context.getString(R.string.files_error_delete, e.name)
    }
    is SecurityException -> context.getString(R.string.files_error_outside)
    else -> when {
        e is IllegalArgumentException && e.message == Workspace.ROOT_ERROR -> context.getString(R.string.files_error_root)
        else -> e.message ?: context.getString(R.string.files_error_failed)
    }
}
