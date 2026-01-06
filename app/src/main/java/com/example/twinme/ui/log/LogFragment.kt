package com.example.twinme.ui.log

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.twinme.R

class LogFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var logAdapter: LogAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_log, container, false)

        recyclerView = root.findViewById(R.id.rv_logs)
        recyclerView.layoutManager = LinearLayoutManager(context).apply {
            reverseLayout = true
            stackFromEnd = true
        }

        logAdapter = LogAdapter(mutableListOf())
        recyclerView.adapter = logAdapter

        // TODO: logs LiveData는 나중에 ViewModel에 추가 필요
        // 현재는 빈 리스트로 초기화

        return root
    }
}
