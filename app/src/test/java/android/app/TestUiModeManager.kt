package android.app

class TestUiModeManager(private val modeType: Int) : UiModeManager() {
    override fun getCurrentModeType(): Int = modeType
}
