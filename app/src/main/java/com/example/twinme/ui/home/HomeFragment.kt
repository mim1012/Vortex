
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment() {

    private val viewModel: CallAcceptViewModel by activityViewModels()
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvRemainingDays: TextView
    private lateinit var tvExpiresAt: TextView
    private lateinit var fabStartStop: FloatingActionButton
    private var isServiceRunning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_home, container, false)

        tvServiceStatus = root.findViewById(R.id.tv_service_status)
        tvRemainingDays = root.findViewById(R.id.tv_remaining_days)
        tvExpiresAt = root.findViewById(R.id.tv_expires_at)
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

        updateLicenseInfo()

        return root
    }

    private fun updateLicenseInfo() {
        activity?.let { activity ->
            val authManager = AuthManager.getInstance(activity)
            val expiresAt = authManager.expiresAt

            if (expiresAt.isNotEmpty()) {
                val remainingDays = calculateRemainingDays(expiresAt)
                val formattedDate = formatExpiresDate(expiresAt)

                tvRemainingDays.text = "${remainingDays}일"
                tvExpiresAt.text = "만료일: $formattedDate"
            } else {
                tvRemainingDays.text = "--일"
                tvExpiresAt.text = "만료일: --"
            }
        }
    }

    private fun calculateRemainingDays(expiresAt: String): Long {
        return try {
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            )

            var expiresDate: Date? = null
            for (format in formats) {
                try {
                    expiresDate = format.parse(expiresAt)
                    if (expiresDate != null) break
                } catch (e: Exception) {
                    continue
                }
            }

            if (expiresDate != null) {
                val diffMs = expiresDate.time - System.currentTimeMillis()
                val days = TimeUnit.MILLISECONDS.toDays(diffMs)
                if (days < 0) 0 else days
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun formatExpiresDate(expiresAt: String): String {
        return try {
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()),
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            )

            var expiresDate: Date? = null
            for (format in formats) {
                try {
                    expiresDate = format.parse(expiresAt)
                    if (expiresDate != null) break
                } catch (e: Exception) {
                    continue
                }
            }

            if (expiresDate != null) {
                val outputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                outputFormat.format(expiresDate)
            } else {
                expiresAt
            }
        } catch (e: Exception) {
            expiresAt
        }
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
