package com.knowyourcase.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.knowyourcase.app.R
import com.knowyourcase.app.data.api.RetrofitClient
import com.knowyourcase.app.databinding.FragmentHomeBinding
import com.knowyourcase.app.ui.history.HistoryAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()
    private var backendPingJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Both scan buttons go to the unified scanner
        binding.cardScanQr.setOnClickListener {
            findNavController().navigate(HomeFragmentDirections.actionHomeToScanner())
        }
        binding.cardManual.setOnClickListener {
            findNavController().navigate(HomeFragmentDirections.actionHomeToManual())
        }
        binding.tvViewAll.setOnClickListener {
            findNavController().navigate(HomeFragmentDirections.actionHomeToHistory())
        }
        binding.btnBackendPing.setOnClickListener {
            checkBackend(wakeIfNeeded = true)
        }

        val adapter = HistoryAdapter { _ ->
            findNavController().navigate(HomeFragmentDirections.actionHomeToHistory())
        }
        binding.rvRecentSearches.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecentSearches.adapter = adapter

        viewModel.recentSearches.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items)
            binding.tvNoHistory.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }

        checkBackend(wakeIfNeeded = false)
    }

    private fun checkBackend(wakeIfNeeded: Boolean) {
        backendPingJob?.cancel()
        backendPingJob = viewLifecycleOwner.lifecycleScope.launch {
            setBackendButton(online = false, enabled = false)
            val attempts = if (wakeIfNeeded) 8 else 1
            var online = false

            for (attempt in 0 until attempts) {
                online = withContext(Dispatchers.IO) {
                    try {
                        RetrofitClient.service.health().isSuccessful
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                }
                if (online) break
                if (attempt < attempts - 1) delay(2_000)
            }

            if (_binding != null) setBackendButton(online = online, enabled = true)
        }
    }

    private fun setBackendButton(online: Boolean, enabled: Boolean) {
        val color = if (online) R.color.backend_online else R.color.backend_offline
        binding.btnBackendPing.backgroundTintList =
            ContextCompat.getColorStateList(requireContext(), color)
        binding.btnBackendPing.isEnabled = enabled
        binding.btnBackendPing.alpha = 1f
        binding.btnBackendPing.contentDescription = getString(
            if (online) R.string.backend_online_description
            else R.string.backend_offline_description
        )
    }

    override fun onDestroyView() {
        backendPingJob?.cancel()
        backendPingJob = null
        super.onDestroyView()
        _binding = null
    }
}
