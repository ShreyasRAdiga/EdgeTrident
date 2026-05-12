package adiga.shreyas.edgetrident

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import adiga.shreyas.edgetrident.ui.theme.EdgeTridentTheme
import adiga.shreyas.edgetrident.vision.camera.CameraStreamScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EdgeTridentTheme {
                CameraStreamScreen(lifecycleOwner = this@MainActivity)
            }
        }
    }
}
