
package com.example.twinme.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.twinme.R
import com.example.twinme.auth.AuthManager
import com.example.twinme.engine.CallAcceptViewModel
import com.example.twinme.service.FloatingStateService
import com.google.android.material.floatingactionbutton.FloatingActionButton

class HomeFragment : Fragment() {

    private val viewModel: CallAcceptViewModel by activityViewModels()
    private lateinit var tvServiceStatus: TextView
    private lateinit var fabStartStop: FloatingActionButton
    private var isServiceRunning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_home, container, false)

        tvServiceStatus = root.findViewById(R.id.tv_service_status)
        fabStartStop = root.findViewById(R.id.fab_start_stop)

        viewModel.currentState.observe(viewLifecycleOwner) {
            tvServiceStatus.text = it.name
        }

        fabStartStop.setOnClickListener {
            if (isServiceRunning) {
                stopFloatingService()
            } else {
                startFloatingService()
            }
        }

        return root
    }

    private fun startFloatingService() {
        activity?.let { activity ->
            // 인증 상태 확인
            val authManager = AuthManager.getInstance(activity)
            if (!authManager.isAuthorized || !authManager.isCacheValid()) {
                Toast.makeText(activity, "인증이 필요합니다.", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(activity, FloatingStateService::class.java)
            activity.startService(intent)
            fabStartStop.setImageResource(R.drawable.ic_stop_white_24dp)
            isServiceRunning = true
        }
    }

    private fun stopFloatingService() {
        activity?.let {
            val intent = Intent(it, FloatingStateService::class.java)
            it.stopService(intent)
            fabStartStop.setImageResource(R.drawable.ic_play_arrow_white_24dp)
            isServiceRunning = false
        }
    }
}
