package com.easycar.core

import kotlin.random.Random

/** Shared by the score colour and the spoken coaching; these bands do not change scoring. */
enum class FeedbackBand {
    PERFECT, CAUTION, WARNING, UNRATED;

    companion object {
        fun from(score:Int?):FeedbackBand=when(score) {
            100->PERFECT
            in 80..99->CAUTION
            in 0..79->WARNING
            else->UNRATED
        }
    }
}

class FeedbackSpeech(random:Random=Random.Default) {
    private val compliments=listOf(
        "잘하고 있습니다.",
        "아주 잘했어요. 지금처럼 해 주세요.",
        "좋아요. 부드럽게 잘 조작했어요.",
        "훌륭해요. 좋은 흐름을 이어 가세요.",
        "멋져요. 차분하게 잘하고 있어요.",
        "잘했어요. 지금의 조작 감각을 기억해 보세요."
    ).shuffled(random)
    private var complimentIndex=0

    fun text(result:ManeuverResult):String {
        val band=FeedbackBand.from(result.score)
        if(band==FeedbackBand.UNRATED) return unrated(result)
        val advice=when(band) {
            FeedbackBand.PERFECT->compliment()
            FeedbackBand.CAUTION->caution(result)
            FeedbackBand.WARNING->warning(result)
            FeedbackBand.UNRATED->error("Handled above")
        }
        return "${result.type.title} ${result.score}점 입니다. $advice"
    }

    /** Spoken presentation only: preserve the stored technical explanation for diagnostics. */
    private fun unrated(result:ManeuverResult):String {
        val name=when(result.type) {
            ManeuverType.UNKNOWN->"동작"
            ManeuverType.PARKING_UNKNOWN->"주차"
            else->result.type.title
        }
        val reason=when(FeedbackSummary.reason(result)) {
            "주차 공간 미확인"->"주차 위치를 확인할 정보가 부족해요."
            "조향 정보 부족"->"핸들 움직임을 확인할 정보가 부족해요."
            "회전 신호 부족","방향 정보 부족"->"회전 방향을 확인할 정보가 부족해요."
            "정렬 정보 부족"->"주차 위치를 확인할 정보가 부족해요."
            "제동 상황 미확인"->"속도를 줄인 상황을 확인하지 못했어요."
            else->"주행 정보가 충분하지 않아요."
        }
        return "$name 평가를 보류했어요. $reason"
    }

    private fun compliment():String {
        val text=compliments[complimentIndex]
        complimentIndex=(complimentIndex+1)%compliments.size
        return text
    }

    private fun caution(result:ManeuverResult):String=when(FeedbackSummary.reason(result)) {
        "고속 회전","속도 높음"->when {
            isTurn(result.type)->"너무 빠른 속도로 회전했어요. ${result.type.title} 시에는 조금 더 천천히 회전하는 게 좋겠어요."
            isParking(result.type)->"주차 중 이동 속도가 높았어요. 조금 더 천천히 위치를 조정해 보세요."
            else->"속도가 너무 높았어요. 조금 더 천천히 운전하는 게 좋겠어요."
        }
        "횡가속 큼"->"회전할 때 차가 옆으로 쏠렸어요. 회전 전에 속도를 줄이고 부드럽게 돌아 보세요."
        "급가속"->"너무 빠르게 가속했어요. 가속 페달을 조금 더 천천히 밟는 게 좋겠어요."
        "급감속"->"속도를 너무 급하게 줄였어요. 충분한 거리를 두고 부드럽게 감속하는 게 좋겠어요."
        "가감속 변화 큼"->"가속과 감속의 변화가 컸어요. 페달을 조금 더 부드럽게 조작해 보세요."
        "조향 변화 큼"->"핸들을 너무 급하게 돌렸어요. 조금 더 천천히 부드럽게 조작하는 게 좋겠어요."
        "전후진 반복"->"주차 중 전후진 전환이 많았어요. 진입 위치를 먼저 확인하고 천천히 조정해 보세요."
        "주차 시간 초과"->"주차 시간이 길어졌어요. 서두르지 말고 주변과 진입 위치를 확인해 보세요."
        "정렬 오차","주차선 침범","진입 자세"->"주차 위치가 맞지 않았어요. 주변과 주차선을 확인하면서 천천히 위치를 조정해 보세요."
        else->"조작 변화가 컸어요. 조금 더 차분하고 부드럽게 조작하는 게 좋겠어요."
    }

    private fun warning(result:ManeuverResult):String=when(FeedbackSummary.reason(result)) {
        "고속 회전","속도 높음"->"너무 빠른 운전은 위험할 수 있습니다. 속도를 줄이세요."
        "횡가속 큼"->"급격한 회전은 위험할 수 있습니다. 회전 전에 속도를 줄이고 핸들을 부드럽게 조작하세요."
        "급가속"->"급가속은 위험할 수 있습니다. 가속 페달을 천천히 밟으세요."
        "급감속"->"급격한 감속은 위험할 수 있습니다. 앞차와 충분한 거리를 두고 부드럽게 감속하세요."
        "가감속 변화 큼"->"급격한 가감속은 위험할 수 있습니다. 페달을 부드럽게 조작하세요."
        "조향 변화 큼"->"급격한 핸들 조작은 위험할 수 있습니다. 속도를 줄이고 핸들을 부드럽게 조작하세요."
        "전후진 반복"->"전후진 전환이 너무 많았습니다. 주변을 충분히 확인한 뒤 천천히 위치를 조정하세요."
        "주차 시간 초과"->"주차 시간이 많이 길어졌습니다. 서두르지 말고 주변을 확인하며 차분하게 조작하세요."
        "정렬 오차","주차선 침범","진입 자세"->"주차 위치를 다시 확인해야 합니다. 주변과 주차선을 확인하고 천천히 위치를 조정하세요."
        else->"급격한 조작은 위험할 수 있습니다. 주변을 확인하고 부드럽게 조작하세요."
    }

    private fun isTurn(type:ManeuverType)=type in setOf(ManeuverType.LEFT,ManeuverType.RIGHT,ManeuverType.U_TURN)
}
