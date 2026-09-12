package com.knowyourcase.app.ui.scan

import android.os.Bundle
import android.text.InputFilter
import android.view.*
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.knowyourcase.app.databinding.FragmentManualEntryBinding
import com.knowyourcase.app.utils.CnrUtils

class ManualEntryFragment : Fragment() {

    private var _binding: FragmentManualEntryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, b: Bundle?): View {
        _binding = FragmentManualEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.etCnr.filters = binding.etCnr.filters + InputFilter.AllCaps()

        val search = {
            val cnr = CnrUtils.normalise(binding.etCnr.text.toString())
            if (CnrUtils.isValid(cnr)) {
                findNavController().navigate(
                    ManualEntryFragmentDirections.actionManualToResult(cnr)
                )
            } else {
                binding.tilCnr.error = "Enter a valid 16-character CNR (e.g. DLHC010230802020)"
            }
        }

        binding.btnSearch.setOnClickListener { search() }
        binding.etCnr.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
