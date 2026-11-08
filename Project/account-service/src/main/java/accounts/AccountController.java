package accounts;

import java.security.Principal;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The sign-in form and the account pages: account details, change password (everyone), and account management
 * (administrators). Each change redirects back with a message, so a refresh never repeats it.
 */
@Controller
public class AccountController {

	private final AccountStore accounts;
	private final AccountService service;
	private final AccountsProperties properties;

	public AccountController(AccountStore accounts, AccountService service, AccountsProperties properties) {
		this.accounts = accounts;
		this.service = service;
		this.properties = properties;
	}

	/** The pages' "Back to the app" link: Academic Scheduling runs elsewhere. */
	@ModelAttribute("appHome")
	public String appHome() {
		return properties.appHome();
	}

	/** Opening this server's address shows the signed-in person's account (or the sign-in form first). */
	@GetMapping("/")
	public String home() {
		return "redirect:/account";
	}

	@GetMapping("/login")
	public String login() {
		return "signin/login";
	}

	@GetMapping("/account")
	public String account(Principal principal, Model model) {
		model.addAttribute("account", accounts.byId(principal.getName()).orElseThrow());
		return "signin/account";
	}

	@GetMapping("/account/password")
	public String passwordForm(Principal principal, Model model) {
		model.addAttribute("account", accounts.byId(principal.getName()).orElseThrow());
		model.addAttribute("minLength", AccountService.MIN_PASSWORD_LENGTH);
		return "signin/password";
	}

	@PostMapping("/account/password")
	public String changePassword(Principal principal, @RequestParam String current, @RequestParam String password,
			@RequestParam String confirmation, RedirectAttributes redirect) {
		return run(redirect, "/account/password", "Your password has been changed.",
				() -> service.changeOwnPassword(principal.getName(), current, password, confirmation));
	}

	@GetMapping("/admin/accounts")
	public String accountList(Principal principal, Model model) {
		model.addAttribute("account", accounts.byId(principal.getName()).orElseThrow());
		model.addAttribute("accounts", accounts.all());
		model.addAttribute("roles", Role.values());
		model.addAttribute("minLength", AccountService.MIN_PASSWORD_LENGTH);
		return "signin/accounts";
	}

	@PostMapping("/admin/accounts")
	public String create(@RequestParam String id, @RequestParam String username, @RequestParam String name,
			@RequestParam(required = false) String email, @RequestParam(required = false) String role,
			@RequestParam(defaultValue = "false") boolean admin, @RequestParam(required = false) String departments,
			@RequestParam(required = false) String homeDepartment, @RequestParam String password,
			@RequestParam String confirmation, RedirectAttributes redirect) {
		return run(redirect, "/admin/accounts", "Account " + id.trim() + " created.",
				() -> service.create(id, username, name, email, role, admin, departments, homeDepartment, password, confirmation));
	}

	@PostMapping("/admin/accounts/{id}/password")
	public String resetPassword(@PathVariable String id, @RequestParam String password,
			@RequestParam String confirmation, RedirectAttributes redirect) {
		return run(redirect, "/admin/accounts", "Password for " + id + " reset.",
				() -> service.resetPassword(id, password, confirmation));
	}

	@PostMapping("/admin/accounts/{id}/enabled")
	public String setEnabled(Principal principal, @PathVariable String id, @RequestParam boolean enabled,
			RedirectAttributes redirect) {
		return run(redirect, "/admin/accounts", (enabled ? "Enabled " : "Disabled ") + id + ".",
				() -> service.setEnabled(principal.getName(), id, enabled));
	}

	private static String run(RedirectAttributes redirect, String back, String success, Runnable change) {
		try {
			change.run();
			redirect.addFlashAttribute("message", success);
		}
		catch (AccountService.RejectedException e) {
			redirect.addFlashAttribute("error", e.getMessage());
		}
		return "redirect:" + back;
	}

}
