package com.arcadesignpro.auroravpn.iab.stripe

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SubscriptionViewModel : ViewModel() {

    private val _pricesLiveData = MutableLiveData<List<Price>>()
    val pricesLiveData: LiveData<List<Price>> get() = _pricesLiveData

    fun fetchPrices() {
        // TODO: implement Stripe price fetch once keys are configured
    }
}
