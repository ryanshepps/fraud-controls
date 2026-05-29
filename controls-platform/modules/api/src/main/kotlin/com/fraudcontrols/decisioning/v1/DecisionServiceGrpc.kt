package com.fraudcontrols.decisioning.v1

import io.grpc.MethodDescriptor
import io.grpc.MethodDescriptor.MethodType
import io.grpc.ServiceDescriptor
import io.grpc.protobuf.ProtoUtils

object DecisionServiceGrpc {
    const val SERVICE_NAME: String = "fraudcontrols.decisioning.v1.DecisionService"

    private val evaluateMethod: MethodDescriptor<EvaluateRequest, EvaluateResponse> =
        MethodDescriptor
            .newBuilder<EvaluateRequest, EvaluateResponse>()
            .setType(MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "Evaluate"))
            .setRequestMarshaller(ProtoUtils.marshaller(EvaluateRequest.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(EvaluateResponse.getDefaultInstance()))
            .build()

    private val getDecisionMethod: MethodDescriptor<GetDecisionRequest, DecisionRecord> =
        MethodDescriptor
            .newBuilder<GetDecisionRequest, DecisionRecord>()
            .setType(MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, "GetDecision"))
            .setRequestMarshaller(ProtoUtils.marshaller(GetDecisionRequest.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(DecisionRecord.getDefaultInstance()))
            .build()

    private val serviceDescriptor: ServiceDescriptor =
        ServiceDescriptor
            .newBuilder(SERVICE_NAME)
            .addMethod(evaluateMethod)
            .addMethod(getDecisionMethod)
            .build()

    @JvmStatic
    fun getServiceDescriptor(): ServiceDescriptor = serviceDescriptor

    @JvmStatic
    fun getEvaluateMethod(): MethodDescriptor<EvaluateRequest, EvaluateResponse> = evaluateMethod

    @JvmStatic
    fun getGetDecisionMethod(): MethodDescriptor<GetDecisionRequest, DecisionRecord> = getDecisionMethod
}
