package com.loopers.interfaces.api.signup;

import com.loopers.application.signup.SignUpInfo;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserService;
import com.loopers.interfaces.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/signup")
public class SignUpV1Controller {

    private final UserService userService;

    @PostMapping
    public ApiResponse<SignUpV1Dto.SignupResponse> signUp(
        @Valid @RequestBody SignUpV1Dto.SignUpRequest request
    ) {
        User user = userService.signUp(request.userId(), request.email(), request.birthDate());
        SignUpInfo info = SignUpInfo.from(user);
        SignUpV1Dto.SignupResponse response = SignUpV1Dto.SignupResponse.from(info, request.gender());
        return ApiResponse.success(response);
    }
}


