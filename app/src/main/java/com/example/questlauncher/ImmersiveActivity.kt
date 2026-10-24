package com.example.questlauncher

import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature

/** Opens like a VR game and shows the launcher as a big floating panel in front of you. */
class ImmersiveActivity : AppSystemActivity() {

    private val viewModel: LauncherViewModel by lazy { LauncherViewModel(application) }

    override fun registerFeatures(): List<SpatialFeature> {
        return listOf(
            VRFeature(this),
            ComposeFeature(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // Picks up installs, uninstalls and updates made while we were away.
        viewModel.refresh()
        // Reconnects to Wireless debugging by itself if you've paired before.
        viewModel.autoConnectQuietly()
    }

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)

        // The launcher panel, about eye height and 1.8 m in front of you.
        Entity.createPanelEntity(
            R.id.panel,
            Transform(Pose(Vector3(0.0f, 1.4f, -1.8f))),
        )
    }

    override fun registerPanels(): List<PanelRegistration> {
        return listOf(
            ComposeViewPanelRegistration(
                R.id.panel,
                composeViewCreator = { _, ctx ->
                    ComposeView(ctx).apply {
                        setContent { LauncherScreen(viewModel) }
                    }
                },
                settingsCreator = {
                    UIPanelSettings(
                        shape = QuadShapeOptions(width = 2.4f, height = 1.4f),
                        display = DpPerMeterDisplayOptions(500f),
                    )
                },
            ),
        )
    }
}
