package cat.tecnocampus.library.expiry;

import jakarta.validation.constraints.NotBlank;

/** Who is at the desk: the member, by e-mail (ids change at every start of the example). */
public record MemberRequest(@NotBlank String memberEmail) {
}
