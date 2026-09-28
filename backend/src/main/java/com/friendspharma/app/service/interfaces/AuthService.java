package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.dto.request.LoginRequest;
import com.friendspharma.app.dto.request.RbacRequest;
import com.friendspharma.app.dto.request.UpdateProfileRequest;
import com.friendspharma.app.dto.response.AuthResponse;
import com.friendspharma.app.dto.response.UserResponse;
import org.springframework.web.multipart.MultipartFile;

public interface AuthService {
    AuthResponse login(LoginRequest request);
    AuthResponse refresh(String refreshToken);
    void logout(String refreshToken);
    UserResponse getCurrentUser();
    UserResponse uploadAvatar(MultipartFile file);

    /** Update the authenticated user's own profile fields. */
    UserResponse updateMyProfile(UpdateProfileRequest request);

    /** Change the authenticated user's own password (current password required). */
    void changeMyPassword(RbacRequest.ChangePasswordRequest request);
}
