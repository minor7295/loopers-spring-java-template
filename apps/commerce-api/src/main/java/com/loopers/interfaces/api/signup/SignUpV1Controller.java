package com.loopers.interfaces.api.signup;

import com.loopers.application.user.UserService;
import com.loopers.domain.user.Gender;
import com.loopers.domain.user.Point;
import com.loopers.domain.user.User;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원가입 API v1 컨트롤러.
 * <p>
 * 사용자 회원가입 요청을 처리하는 REST API를 제공합니다.
 * </p>
 *
 * @author Loopers
 * @version 1.0
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/signup")
public class SignUpV1Controller {

    private final UserService userService;

    /**
     * 회원가입을 처리합니다.
     *
     * @param request 회원가입 요청 데이터 (userId, email, birthDate, gender)
     * @return 생성된 사용자 정보를 담은 API 응답
     * @throws CoreException gender 값이 유효하지 않거나, 유효성 검증 실패 또는 중복 ID 존재 시
     */
    @PostMapping
    public ApiResponse<SignUpV1Dto.SignupResponse> signUp(
        @Valid @RequestBody SignUpV1Dto.SignUpRequest request
    ) {
        Gender gender = parseGender(request.gender());
        User user = userService.create(
            request.userId(),
            request.email(),
            request.birthDate(),
            gender,
            Point.of(0L)
        );
        SignUpV1Dto.SignupResponse response = SignUpV1Dto.SignupResponse.from(user);
        return ApiResponse.success(response);
    }

    /**
     * 성별 문자열을 Gender enum으로 변환합니다.
     *
     * @param genderStr 성별 문자열 (MALE 또는 FEMALE)
     * @return Gender enum
     * @throws CoreException 유효하지 않은 성별 값인 경우
     */
    private Gender parseGender(String genderStr) {
        try {
            return Gender.valueOf(genderStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CoreException(
                ErrorType.BAD_REQUEST,
                String.format("유효하지 않은 성별입니다. (gender: %s)", genderStr)
            );
        }
    }
}
