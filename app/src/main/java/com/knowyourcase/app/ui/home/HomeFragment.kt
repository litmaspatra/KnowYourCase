package com.knowyourcase.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()
    private var backendStatusJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
            checkBackend()
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    checkBackend()
                    delay(60_000)
                }
            }
        }
    }

    private fun checkBackend() {
        backendStatusJob?.cancel()
        backendStatusJob = viewLifecycleOwner.lifecycleScope.launch {
            val online = withContext(Dispatchers.IO) {
                try {
                    RetrofitClient.service.health().isSuccessful
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }

            if (_binding != null) {
                setBackendStatus(online)
            }
        }
    }

    private fun setBackendStatus(online: Boolean) {
        val color = if (online) R.color.backend_online else R.color.backend_offline
        binding.btnBackendPing.backgroundTintList =
            ContextCompat.getColorStateList(requireContext(), color)
        binding.btnBackendPing.isEnabled = true
        binding.btnBackendPing.alpha = 1f
        binding.btnBackendPing.contentDescription = getString(
            if (online) R.string.backend_online_description
            else R.string.backend_offline_description
        )
    }

    override fun onDestroyView() {
        backendStatusJob?.cancel()
        backendStatusJob = null
        super.onDestroyView()
        _binding = null
    }
}
