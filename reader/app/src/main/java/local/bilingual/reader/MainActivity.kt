package local.bilingual.reader
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.edit
import java.io.File
import local.bilingual.reader.data.*
import local.bilingual.reader.ui.*


const val EXTRA_BOOK_PATH = "bookPath"

class MainActivity : ComponentActivity() {
    private val model: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Test and adb hook: start with a book file given by absolute path (before the model reads the saved book).
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_BOOK_PATH)?.let { path ->
            File(path).inputStream().use { input -> File(filesDir, "book.zip").outputStream().use { input.copyTo(it) } }
            getSharedPreferences("reader", MODE_PRIVATE).edit { clear() }
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ReaderApp(this, model) }
    }
}
